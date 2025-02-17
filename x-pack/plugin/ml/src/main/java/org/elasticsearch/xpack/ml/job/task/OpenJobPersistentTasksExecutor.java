/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.job.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata.Assignment;
import org.elasticsearch.persistent.decider.EnableAssignmentDecider;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.xpack.core.ml.MlConfigIndex;
import org.elasticsearch.xpack.core.ml.MlMetaIndex;
import org.elasticsearch.xpack.core.ml.MlTasks;
import org.elasticsearch.xpack.core.ml.action.FinalizeJobExecutionAction;
import org.elasticsearch.xpack.core.ml.action.GetJobsAction;
import org.elasticsearch.xpack.core.ml.action.OpenJobAction;
import org.elasticsearch.xpack.core.ml.action.ResetJobAction;
import org.elasticsearch.xpack.core.ml.action.RevertModelSnapshotAction;
import org.elasticsearch.xpack.core.ml.job.config.Blocked;
import org.elasticsearch.xpack.core.ml.job.config.Job;
import org.elasticsearch.xpack.core.ml.job.config.JobState;
import org.elasticsearch.xpack.core.ml.job.config.JobTaskState;
import org.elasticsearch.xpack.core.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.core.ml.job.persistence.ElasticsearchMappings;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.datafeed.persistence.DatafeedConfigProvider;
import org.elasticsearch.xpack.ml.job.JobNodeSelector;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsProvider;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcessManager;
import org.elasticsearch.xpack.ml.notifications.AnomalyDetectionAuditor;
import org.elasticsearch.xpack.ml.process.MlMemoryTracker;
import org.elasticsearch.xpack.ml.task.AbstractJobPersistentTasksExecutor;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;
import static org.elasticsearch.xpack.core.ml.MlTasks.AWAITING_UPGRADE;
import static org.elasticsearch.xpack.core.ml.MlTasks.PERSISTENT_TASK_MASTER_NODE_TIMEOUT;
import static org.elasticsearch.xpack.ml.job.JobNodeSelector.AWAITING_LAZY_ASSIGNMENT;

public class OpenJobPersistentTasksExecutor extends AbstractJobPersistentTasksExecutor<OpenJobAction.JobParams> {

    private static final Logger logger = LogManager.getLogger(OpenJobPersistentTasksExecutor.class);

    // Resuming a job with a running datafeed from its current snapshot was added in 7.11 and
    // can only be done if the master node is on or after that version.
    private static final Version MIN_MASTER_NODE_VERSION_FOR_REVERTING_TO_CURRENT_SNAPSHOT = Version.V_7_11_0;

    public static String[] indicesOfInterest(String resultsIndex) {
        if (resultsIndex == null) {
            return new String[]{AnomalyDetectorsIndex.jobStateIndexPattern(), MlMetaIndex.indexName(),
                MlConfigIndex.indexName()};
        }
        return new String[]{AnomalyDetectorsIndex.jobStateIndexPattern(), resultsIndex, MlMetaIndex.indexName(),
            MlConfigIndex.indexName()};
    }

    private final AutodetectProcessManager autodetectProcessManager;
    private final DatafeedConfigProvider datafeedConfigProvider;
    private final Client client;
    private final JobResultsProvider jobResultsProvider;
    private final AnomalyDetectionAuditor auditor;
    private final XPackLicenseState licenseState;

    private volatile ClusterState clusterState;

    public OpenJobPersistentTasksExecutor(Settings settings,
                                          ClusterService clusterService,
                                          AutodetectProcessManager autodetectProcessManager,
                                          DatafeedConfigProvider datafeedConfigProvider,
                                          MlMemoryTracker memoryTracker,
                                          Client client,
                                          IndexNameExpressionResolver expressionResolver,
                                          XPackLicenseState licenseState) {
        super(MlTasks.JOB_TASK_NAME, MachineLearning.UTILITY_THREAD_POOL_NAME, settings, clusterService, memoryTracker, expressionResolver);
        this.autodetectProcessManager = Objects.requireNonNull(autodetectProcessManager);
        this.datafeedConfigProvider = Objects.requireNonNull(datafeedConfigProvider);
        this.client = Objects.requireNonNull(client);
        this.jobResultsProvider = new JobResultsProvider(client, settings, expressionResolver);
        this.auditor = new AnomalyDetectionAuditor(client, clusterService);
        this.licenseState = licenseState;
        clusterService.addListener(event -> clusterState = event.state());
    }

    @Override
    public Assignment getAssignment(OpenJobAction.JobParams params, Collection<DiscoveryNode> candidateNodes, ClusterState clusterState) {
        // If the task parameters do not have a job field then the job
        // was first opened on a pre v6.6 node and has not been migrated
        Job job = params.getJob();
        if (job == null) {
            return AWAITING_MIGRATION;
        }
        boolean isMemoryTrackerRecentlyRefreshed = memoryTracker.isRecentlyRefreshed();
        Optional<Assignment> optionalAssignment = getPotentialAssignment(params, clusterState, isMemoryTrackerRecentlyRefreshed);
        // NOTE: this will return here if isMemoryTrackerRecentlyRefreshed is false, we don't allow assignment with stale memory
        if (optionalAssignment.isPresent()) {
            return optionalAssignment.get();
        }

        JobNodeSelector jobNodeSelector = new JobNodeSelector(clusterState, candidateNodes, params.getJobId(),
            MlTasks.JOB_TASK_NAME, memoryTracker, job.allowLazyOpen() ? Integer.MAX_VALUE : maxLazyMLNodes, node -> nodeFilter(node, job));
        Assignment assignment = jobNodeSelector.selectNode(
            maxOpenJobs,
            maxConcurrentJobAllocations,
            maxMachineMemoryPercent,
            maxNodeMemory,
            useAutoMemoryPercentage);
        auditRequireMemoryIfNecessary(params.getJobId(), auditor, assignment, jobNodeSelector, isMemoryTrackerRecentlyRefreshed);
        return assignment;
    }

    private static boolean nodeSupportsModelSnapshotVersion(DiscoveryNode node, Job job) {
        if (job.getModelSnapshotId() == null || job.getModelSnapshotMinVersion() == null) {
            // There is no snapshot to restore or the min model snapshot version is 5.5.0
            // which is OK as we have already checked the node is >= 5.5.0.
            return true;
        }
        return node.getVersion().onOrAfter(job.getModelSnapshotMinVersion());
    }

    public static String nodeFilter(DiscoveryNode node, Job job) {

        String jobId = job.getId();

        if (nodeSupportsModelSnapshotVersion(node, job) == false) {
            return "Not opening job [" + jobId + "] on node [" + JobNodeSelector.nodeNameAndVersion(node)
                + "], because the job's model snapshot requires a node of version ["
                + job.getModelSnapshotMinVersion() + "] or higher";
        }

        if (Job.getCompatibleJobTypes(node.getVersion()).contains(job.getJobType()) == false) {
            return "Not opening job [" + jobId + "] on node [" + JobNodeSelector.nodeNameAndVersion(node) +
                "], because this node does not support jobs of type [" + job.getJobType() + "]";
        }

        return null;
    }

    static void validateJobAndId(String jobId, Job job) {
        if (job == null) {
            throw ExceptionsHelper.missingJobException(jobId);
        }
        if (job.getBlocked().getReason() != Blocked.Reason.NONE) {
            throw ExceptionsHelper.conflictStatusException("Cannot open job [{}] because it is executing [{}]", jobId,
                job.getBlocked().getReason());
        }
        if (job.getJobVersion() == null) {
            throw ExceptionsHelper.badRequestException(
                "Cannot open job [{}] because jobs created prior to version 5.5 are not supported",
                jobId);
        }
    }

    @Override
    public void validate(OpenJobAction.JobParams params, ClusterState clusterState) {
        final Job job = params.getJob();
        final String jobId = params.getJobId();
        validateJobAndId(jobId, job);
        // If we already know that we can't find an ml node because all ml nodes are running at capacity or
        // simply because there are no ml nodes in the cluster then we fail quickly here:
        PersistentTasksCustomMetadata.Assignment assignment = getAssignment(params, clusterState.nodes().getAllNodes(), clusterState);
        if (assignment.equals(AWAITING_UPGRADE)) {
            throw makeCurrentlyBeingUpgradedException(logger, params.getJobId());
        }

        if (assignment.getExecutorNode() == null && assignment.equals(AWAITING_LAZY_ASSIGNMENT) == false) {
            throw makeNoSuitableNodesException(logger, params.getJobId(), assignment.getExplanation());
        }
    }

    @Override
    // Exceptions that occur while the node is dying, i.e. after the JVM has received a SIGTERM,
    // are ignored.  Core services will be stopping in response to the SIGTERM and we want the
    // job to try to open again on another node, not spuriously fail on the dying node.
    protected void nodeOperation(AllocatedPersistentTask task, OpenJobAction.JobParams params, PersistentTaskState state) {
        JobTask jobTask = (JobTask) task;
        jobTask.setAutodetectProcessManager(autodetectProcessManager);
        JobTaskState jobTaskState = (JobTaskState) state;
        JobState jobState = jobTaskState == null ? null : jobTaskState.getState();
        ActionListener<Boolean> resultsMappingUpdateHandler = ActionListener.wrap(
            mappingsUpdate -> jobResultsProvider.setRunningForecastsToFailed(params.getJobId(), ActionListener.wrap(
                r -> runJob(jobTask, jobState, params),
                e -> {
                    if (autodetectProcessManager.isNodeDying() == false) {
                        logger.warn(new ParameterizedMessage("[{}] failed to set forecasts to failed", params.getJobId()), e);
                        runJob(jobTask, jobState, params);
                    }
                }
            )),
            e -> {
                if (autodetectProcessManager.isNodeDying() == false) {
                    logger.error(new ParameterizedMessage("[{}] Failed to update results mapping", params.getJobId()), e);
                    failTask(jobTask, "failed to update results mapping");
                }
            }
        );
        // We need to update the results index as we MAY update the current forecast results, setting the running forcasts to failed
        // This writes to the results index, which might need updating
        ElasticsearchMappings.addDocMappingIfMissing(
            AnomalyDetectorsIndex.jobResultsAliasedName(params.getJobId()),
            AnomalyDetectorsIndex::wrappedResultsMapping,
            client,
            clusterState,
            PERSISTENT_TASK_MASTER_NODE_TIMEOUT,
            resultsMappingUpdateHandler);
    }

    // Exceptions that occur while the node is dying, i.e. after the JVM has received a SIGTERM,
    // are ignored.  Core services will be stopping in response to the SIGTERM and we want the
    // job to try to open again on another node, not spuriously fail on the dying node.
    private void runJob(JobTask jobTask, JobState jobState, OpenJobAction.JobParams params) {
        // If the node is already running its exit handlers then do nothing - shortly
        // the persistent task will get assigned to a new node and the code below will
        // run there instead.
        if (autodetectProcessManager.isNodeDying()) {
            return;
        }
        // If the job is closing, simply stop and return
        if (JobState.CLOSING.equals(jobState)) {
            // Mark as completed instead of using `stop` as stop assumes native processes have started
            logger.info("[{}] job got reassigned while stopping. Marking as completed", params.getJobId());
            jobTask.markAsCompleted();
            return;
        }
        // If the job is failed then the Persistent Task Service will
        // try to restart it on a node restart. Exiting here leaves the
        // job in the failed state and it must be force closed.
        if (JobState.FAILED.equals(jobState)) {
            return;
        }

        ActionListener<Boolean> hasRunningDatafeedTaskListener = ActionListener.wrap(
            hasRunningDatafeed -> {
                if (hasRunningDatafeed && isMasterNodeVersionOnOrAfter(MIN_MASTER_NODE_VERSION_FOR_REVERTING_TO_CURRENT_SNAPSHOT)) {

                    // This job has a running datafeed attached to it.
                    // In order to prevent gaps in the model we revert to the current snapshot deleting intervening results.
                    revertToCurrentSnapshot(jobTask.getJobId(), ActionListener.wrap(
                        response -> openJob(jobTask),
                        e -> {
                            if (autodetectProcessManager.isNodeDying() == false) {
                                logger.error(new ParameterizedMessage("[{}] failed to revert to current snapshot", jobTask.getJobId()), e);
                                failTask(jobTask, "failed to revert to current snapshot");
                            }
                        }
                    ));
                } else {
                    openJob(jobTask);
                }
            },
            e -> {
                if (autodetectProcessManager.isNodeDying() == false) {
                    logger.error(new ParameterizedMessage("[{}] failed to search for associated datafeed", jobTask.getJobId()), e);
                    failTask(jobTask, "failed to search for associated datafeed");
                }
            }
        );

        hasRunningDatafeedTask(jobTask.getJobId(), hasRunningDatafeedTaskListener);
    }

    private void failTask(JobTask jobTask, String reason) {
        JobTaskState failedState = new JobTaskState(JobState.FAILED, jobTask.getAllocationId(), reason);
        jobTask.updatePersistentTaskState(failedState, ActionListener.wrap(
            r -> logger.debug(() -> new ParameterizedMessage("[{}] updated task state to failed", jobTask.getJobId())),
            e -> {
                logger.error(new ParameterizedMessage("[{}] error while setting task state to failed; marking task as failed",
                    jobTask.getJobId()), e);
                jobTask.markAsFailed(e);
            }
        ));
    }

    private boolean isMasterNodeVersionOnOrAfter(Version version) {
        return clusterState.nodes().getMasterNode().getVersion().onOrAfter(version);
    }

    private void hasRunningDatafeedTask(String jobId, ActionListener<Boolean> listener) {
        ActionListener<Set<String>> datafeedListener = ActionListener.wrap(
            datafeeds -> {
                assert datafeeds.size() <= 1;
                if (datafeeds.isEmpty()) {
                    listener.onResponse(false);
                    return;
                }

                String datafeedId = datafeeds.iterator().next();
                PersistentTasksCustomMetadata tasks = clusterState.getMetadata().custom(PersistentTasksCustomMetadata.TYPE);
                PersistentTasksCustomMetadata.PersistentTask<?> datafeedTask = MlTasks.getDatafeedTask(datafeedId, tasks);
                listener.onResponse(datafeedTask != null);
            },
            listener::onFailure
        );

        datafeedConfigProvider.findDatafeedIdsForJobIds(Collections.singleton(jobId), datafeedListener);
    }

    private void revertToCurrentSnapshot(String jobId, ActionListener<Boolean> listener) {
        ActionListener<GetJobsAction.Response> jobListener = ActionListener.wrap(
            jobResponse -> {
                List<Job> jobPage = jobResponse.getResponse().results();
                // We requested a single concrete job so if it didn't exist we would get an error
                assert jobPage.size() == 1;

                String jobSnapshotId = jobPage.get(0).getModelSnapshotId();
                if (jobSnapshotId == null && isMasterNodeVersionOnOrAfter(ResetJobAction.VERSION_INTRODUCED)) {
                    logger.info("[{}] job has running datafeed task; resetting as no snapshot exists", jobId);
                    ResetJobAction.Request request = new ResetJobAction.Request(jobId);
                    request.setSkipJobStateValidation(true);
                    request.masterNodeTimeout(PERSISTENT_TASK_MASTER_NODE_TIMEOUT);
                    request.timeout(PERSISTENT_TASK_MASTER_NODE_TIMEOUT);
                    executeAsyncWithOrigin(client, ML_ORIGIN, ResetJobAction.INSTANCE, request, ActionListener.wrap(
                        response -> listener.onResponse(true),
                        listener::onFailure
                    ));
                } else {
                    logger.info("[{}] job has running datafeed task; reverting to current snapshot", jobId);
                    RevertModelSnapshotAction.Request request = new RevertModelSnapshotAction.Request(jobId,
                        jobSnapshotId == null ? ModelSnapshot.EMPTY_SNAPSHOT_ID : jobSnapshotId);
                    request.setForce(true);
                    request.setDeleteInterveningResults(true);
                    request.masterNodeTimeout(PERSISTENT_TASK_MASTER_NODE_TIMEOUT);
                    executeAsyncWithOrigin(client, ML_ORIGIN, RevertModelSnapshotAction.INSTANCE, request, ActionListener.wrap(
                        response -> listener.onResponse(true),
                        listener::onFailure
                    ));
                }
            },
            error -> listener.onFailure(ExceptionsHelper.serverError("[{}] error getting job", error, jobId))
        );

        // We need to refetch the job in order to learn what is its current model snapshot
        // as the one that exists in the task params is outdated.
        GetJobsAction.Request request = new GetJobsAction.Request(jobId);
        request.masterNodeTimeout(PERSISTENT_TASK_MASTER_NODE_TIMEOUT);
        executeAsyncWithOrigin(client, ML_ORIGIN, GetJobsAction.INSTANCE, request, jobListener);
    }

    // Exceptions that occur while the node is dying, i.e. after the JVM has received a SIGTERM,
    // are ignored.  Core services will be stopping in response to the SIGTERM and we want the
    // job to try to open again on another node, not spuriously fail on the dying node.
    private void openJob(JobTask jobTask) {
        String jobId = jobTask.getJobId();
        autodetectProcessManager.openJob(jobTask, clusterState, PERSISTENT_TASK_MASTER_NODE_TIMEOUT, (e2, shouldFinalizeJob) -> {
            if (e2 == null) {
                // Beyond this point it's too late to change our minds about whether we're closing or vacating
                if (jobTask.isVacating()) {
                    jobTask.markAsLocallyAborted(
                        "previously assigned node [" + clusterState.nodes().getLocalNode().getName() + "] is shutting down");
                } else if (shouldFinalizeJob) {
                    FinalizeJobExecutionAction.Request finalizeRequest = new FinalizeJobExecutionAction.Request(new String[]{jobId});
                    finalizeRequest.masterNodeTimeout(PERSISTENT_TASK_MASTER_NODE_TIMEOUT);
                    executeAsyncWithOrigin(client, ML_ORIGIN, FinalizeJobExecutionAction.INSTANCE, finalizeRequest,
                        ActionListener.wrap(
                            response -> jobTask.markAsCompleted(),
                            e -> {
                                // This error is logged even if the node is dying.  This is a nasty place for the node to get killed,
                                // as most of the job's close sequence has executed, just not the finalization step.  The job will
                                // restart on a different node.  If the coordinating node for the close request notices that the job
                                // changed nodes while waiting for it to close then it will remove the persistent task, which should
                                // stop the job doing anything significant on its new node.  However, the finish time of the job will
                                // not be set correctly.
                                logger.error(new ParameterizedMessage("[{}] error finalizing job", jobId), e);
                                Throwable unwrapped = ExceptionsHelper.unwrapCause(e);
                                if (unwrapped instanceof DocumentMissingException || unwrapped instanceof ResourceNotFoundException) {
                                    jobTask.markAsCompleted();
                                } else if (autodetectProcessManager.isNodeDying() == false) {
                                    // In this case we prefer to mark the task as failed, which means the job
                                    // will appear closed. The reason is that the job closed successfully and
                                    // we just failed to update some fields like the finish time. It is preferable
                                    // to let the job close than setting it to failed.
                                    jobTask.markAsFailed(e);
                                }
                            }
                        ));
                } else {
                    jobTask.markAsCompleted();
                }
            } else if (autodetectProcessManager.isNodeDying() == false) {
                logger.error(new ParameterizedMessage("[{}] failed to open job", jobTask.getJobId()), e2);
                failTask(jobTask, "failed to open job");
            }
        });
    }

    @Override
    protected AllocatedPersistentTask createTask(long id, String type, String action, TaskId parentTaskId,
                                                 PersistentTasksCustomMetadata.PersistentTask<OpenJobAction.JobParams> persistentTask,
                                                 Map<String, String> headers) {
        return new JobTask(persistentTask.getParams().getJobId(), id, type, action, parentTaskId, headers, licenseState);
    }

    public static Optional<ElasticsearchException> checkAssignmentState(PersistentTasksCustomMetadata.Assignment assignment,
                                                                        String jobId,
                                                                        Logger logger) {
        if (assignment != null
            && assignment.equals(PersistentTasksCustomMetadata.INITIAL_ASSIGNMENT) == false
            && assignment.isAssigned() == false) {
            // Assignment has failed on the master node despite passing our "fast fail" validation
            if (assignment.equals(AWAITING_UPGRADE)) {
                return Optional.of(makeCurrentlyBeingUpgradedException(logger, jobId));
            } else if (assignment.getExplanation().contains("[" + EnableAssignmentDecider.ALLOCATION_NONE_EXPLANATION + "]")) {
                return Optional.of(makeAssignmentsNotAllowedException(logger, jobId));
            } else {
                return Optional.of(makeNoSuitableNodesException(logger, jobId, assignment.getExplanation()));
            }
        }
        return Optional.empty();
    }

    static ElasticsearchException makeNoSuitableNodesException(Logger logger, String jobId, String explanation) {
        String msg = "Could not open job because no suitable nodes were found, allocation explanation [" + explanation + "]";
        logger.warn("[{}] {}", jobId, msg);
        Exception detail = new IllegalStateException(msg);
        return new ElasticsearchStatusException("Could not open job because no ML nodes with sufficient capacity were found",
            RestStatus.TOO_MANY_REQUESTS, detail);
    }

    static ElasticsearchException makeAssignmentsNotAllowedException(Logger logger, String jobId) {
        String msg = "Cannot open jobs because persistent task assignment is disabled by the ["
            + EnableAssignmentDecider.CLUSTER_TASKS_ALLOCATION_ENABLE_SETTING.getKey() + "] setting";
        logger.warn("[{}] {}", jobId, msg);
        return new ElasticsearchStatusException(msg, RestStatus.TOO_MANY_REQUESTS);
    }

    static ElasticsearchException makeCurrentlyBeingUpgradedException(Logger logger, String jobId) {
        String msg = "Cannot open jobs when upgrade mode is enabled";
        logger.warn("[{}] {}", jobId, msg);
        return new ElasticsearchStatusException(msg, RestStatus.TOO_MANY_REQUESTS);
    }

    @Override
    protected String[] indicesOfInterest(OpenJobAction.JobParams params) {
        return indicesOfInterest(AnomalyDetectorsIndex.resultsWriteAlias(params.getJobId()));
    }

    @Override
    protected String getJobId(OpenJobAction.JobParams params) {
        return params.getJobId();
    }
}
