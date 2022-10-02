/*
 * Copyright 2022 Orkes, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.orkes.conductor.client.automator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.conductor.client.config.PropertyFactory;
import com.netflix.conductor.client.telemetry.MetricsContainer;
import com.netflix.conductor.client.worker.Worker;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.discovery.EurekaClient;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Spectator;

import io.orkes.conductor.client.TaskClient;
import io.orkes.conductor.client.http.ApiException;

import com.google.common.base.Stopwatch;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class TaskRunner {
    private static final Registry REGISTRY = Spectator.globalRegistry();

    private final EurekaClient eurekaClient;
    private final TaskClient taskClient;
    private final int updateRetryCount;
    private final ThreadPoolExecutor executorService;
    private final Map<String /* taskType */, String /* domain */> taskToDomain;
    private final int threadCount;
    private final int taskPollTimeout;
    private static final String OVERRIDE_DISCOVERY = "pollOutOfDiscovery";

    private static final String ALL_WORKERS = "all";

    private static final String DOMAIN = "domain";

    private Worker worker;

    TaskRunner(
            Worker worker,
            EurekaClient eurekaClient,
            TaskClient taskClient,
            int updateRetryCount,
            Map<String, String> taskToDomain,
            String workerNamePrefix,
            int threadCount,
            int taskPollTimeout) {
        this.worker = worker;
        this.eurekaClient = eurekaClient;
        this.taskClient = taskClient;
        this.updateRetryCount = updateRetryCount;
        this.taskToDomain = taskToDomain;
        this.threadCount = threadCount;
        this.taskPollTimeout = taskPollTimeout;
        this.executorService =
                (ThreadPoolExecutor)
                        Executors.newFixedThreadPool(
                                threadCount,
                                new BasicThreadFactory.Builder()
                                        .namingPattern(workerNamePrefix)
                                        .uncaughtExceptionHandler(uncaughtExceptionHandler)
                                        .build());
        log.info(
                "Initialized the TaskPollExecutor for {} with {} threads and threadPrefix {}",
                threadCount,
                threadCount,
                workerNamePrefix);
    }

    public void pollAndExecute() {
        try {
            List<Task> tasks = pollTasksForWorker();
            tasks.forEach(
                    task -> this.executorService.submit(() -> this.processTask(task, worker)));
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
        }
    }

    public void shutdownExecutorService(int timeout) {
        try {
            this.executorService.shutdown();
            if (executorService.awaitTermination(timeout, TimeUnit.SECONDS)) {
                log.debug("tasks completed, shutting down");
            } else {
                log.warn(String.format("forcing shutdown after waiting for %s second", timeout));
                executorService.shutdownNow();
            }
        } catch (InterruptedException ie) {
            log.warn("shutdown interrupted, invoking shutdownNow");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private List<Task> pollTasksForWorker() {
        log.trace("Polling for {}", worker.getTaskDefName());
        List<Task> tasks = new LinkedList<>();
        Boolean discoveryOverride =
                Optional.ofNullable(
                                PropertyFactory.getBoolean(
                                        worker.getTaskDefName(), OVERRIDE_DISCOVERY, null))
                        .orElseGet(
                                () ->
                                        PropertyFactory.getBoolean(
                                                ALL_WORKERS, OVERRIDE_DISCOVERY, false));
        if (eurekaClient != null
                && !eurekaClient.getInstanceRemoteStatus().equals(InstanceInfo.InstanceStatus.UP)
                && !discoveryOverride) {
            log.trace("Instance is NOT UP in discovery - will not poll");
            return tasks;
        }
        if (worker.paused()) {
            MetricsContainer.incrementTaskPausedCount(worker.getTaskDefName());
            log.trace("Worker {} has been paused. Not polling anymore!", worker.getClass());
            return tasks;
        }
        String taskType = worker.getTaskDefName();
        try {
            String domain =
                    Optional.ofNullable(PropertyFactory.getString(taskType, DOMAIN, null))
                            .orElseGet(
                                    () ->
                                            Optional.ofNullable(
                                                            PropertyFactory.getString(
                                                                    ALL_WORKERS, DOMAIN, null))
                                                    .orElse(taskToDomain.get(taskType)));
            log.trace("Polling task of type: {} in domain: '{}'", taskType, domain);
            List<Task> polledTasks =
                    MetricsContainer.getPollTimer(taskType)
                            .record(
                                    () ->
                                            pollTask(
                                                    taskType,
                                                    worker.getIdentity(),
                                                    domain,
                                                    this.getAvailableWorkers()));
            for (Task task : polledTasks) {
                if (Objects.nonNull(task) && StringUtils.isNotBlank(task.getTaskId())) {
                    log.trace(
                            "Polled task: {} of type: {} in domain: '{}', from worker: {}",
                            task.getTaskId(),
                            taskType,
                            domain,
                            worker.getIdentity());
                    tasks.add(task);
                }
            }
        } catch (ApiException ae) {
            MetricsContainer.incrementTaskPollErrorCount(worker.getTaskDefName(), ae);
            log.error(
                    "Error when polling for tasks {} - {}", ae.getCode(), ae.getResponseBody(), ae);
        } catch (Exception e) {
            MetricsContainer.incrementTaskPollErrorCount(worker.getTaskDefName(), e);
            log.error("Error when polling for tasks", e);
        }
        return tasks;
    }

    private int getAvailableWorkers() {
        return (this.threadCount) - this.executorService.getActiveCount();
    }

    private List<Task> pollTask(String taskType, String workerId, String domain, int count) {
        if (count < 1) {
            return Collections.emptyList();
        }
        log.trace("poll {} in the domain {} with batch size {}", taskType, domain, count);
        return taskClient.batchPollTasksInDomain(
                taskType, domain, workerId, count, this.taskPollTimeout);
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
            (thread, error) -> {
                // JVM may be in unstable state, try to send metrics then exit
                MetricsContainer.incrementUncaughtExceptionCount();
                log.error("Uncaught exception. Thread {} will exit now", thread, error);
            };

    private void processTask(Task task, Worker worker) {
        log.trace(
                "Executing task: {} of type: {} in worker: {} at {}",
                task.getTaskId(),
                task.getTaskDefName(),
                worker.getClass().getSimpleName(),
                worker.getIdentity());
        try {
            executeTask(worker, task);
        } catch (Throwable t) {
            task.setStatus(Task.Status.FAILED);
            TaskResult result = new TaskResult(task);
            handleException(t, result, worker, task);
        }
    }

    private void executeTask(Worker worker, Task task) {
        if (task == null || task.getTaskDefName().isEmpty()) {
            log.warn("Empty task {}", worker.getTaskDefName());
            return;
        }
        Stopwatch stopwatch = Stopwatch.createStarted();
        TaskResult result = null;
        try {
            log.trace(
                    "Executing task: {} in worker: {} at {}",
                    task.getTaskId(),
                    worker.getClass().getSimpleName(),
                    worker.getIdentity());
            result = worker.execute(task);
            result.setWorkflowInstanceId(task.getWorkflowInstanceId());
            result.setTaskId(task.getTaskId());
            result.setWorkerId(worker.getIdentity());
        } catch (Exception e) {
            log.error(
                    "Unable to execute task: {} of type: {}",
                    task.getTaskId(),
                    task.getTaskDefName(),
                    e);
            MetricsContainer.incrementTaskExecutionErrorCount(task.getTaskType(), e.getCause());
            if (result == null) {
                task.setStatus(Task.Status.FAILED);
                result = new TaskResult(task);
            }
            handleException(e, result, worker, task);
        } finally {
            stopwatch.stop();
            MetricsContainer.getExecutionTimer(worker.getTaskDefName())
                    .record(stopwatch.elapsed(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
        }
        log.trace(
                "Task: {} executed by worker: {} at {} with status: {}",
                task.getTaskId(),
                worker.getClass().getSimpleName(),
                worker.getIdentity(),
                result.getStatus());
        updateTaskResult(updateRetryCount, task, result, worker);
    }

    private void updateTaskResult(int count, Task task, TaskResult result, Worker worker) {
        try {
            // upload if necessary
            Optional<String> optionalExternalStorageLocation =
                    retryOperation(
                            (TaskResult taskResult) -> upload(taskResult, task.getTaskType()),
                            count,
                            result,
                            "evaluateAndUploadLargePayload");

            if (optionalExternalStorageLocation.isPresent()) {
                result.setExternalOutputPayloadStoragePath(optionalExternalStorageLocation.get());
                result.setOutputData(null);
            }

            retryOperation(
                    (TaskResult taskResult) -> {
                        taskClient.updateTask(taskResult);
                        return null;
                    },
                    count,
                    result,
                    "updateTask");
        } catch (Exception e) {
            worker.onErrorUpdate(task);
            MetricsContainer.incrementTaskUpdateErrorCount(worker.getTaskDefName(), e);
            log.error(
                    String.format(
                            "Failed to update result: %s for task: %s in worker: %s",
                            result.toString(), task.getTaskDefName(), worker.getIdentity()),
                    e);
        }
    }

    private Optional<String> upload(TaskResult result, String taskType) {
        // do nothing
        return Optional.empty();
    }

    private <T, R> R retryOperation(Function<T, R> operation, int count, T input, String opName) {
        int index = 0;
        while (index < count) {
            try {
                return operation.apply(input);
            } catch (Exception e) {
                log.error("Exception {}, retrying...", e.getMessage(), e);
                index++;
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ie) {
                    log.error("Retry interrupted", ie);
                }
            }
        }
        throw new RuntimeException("Exhausted retries performing " + opName);
    }

    private void handleException(Throwable t, TaskResult result, Worker worker, Task task) {
        log.error(String.format("Error while executing task %s", task.toString()), t);
        MetricsContainer.incrementTaskExecutionErrorCount(worker.getTaskDefName(), t);
        result.setStatus(TaskResult.Status.FAILED);
        result.setReasonForIncompletion("Error while executing the task: " + t);
        StringWriter stringWriter = new StringWriter();
        t.printStackTrace(new PrintWriter(stringWriter));
        result.log(stringWriter.toString());
        updateTaskResult(updateRetryCount, task, result, worker);
    }
}
