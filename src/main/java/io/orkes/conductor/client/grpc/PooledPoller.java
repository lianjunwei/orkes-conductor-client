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
package io.orkes.conductor.client.grpc;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.netflix.conductor.client.worker.Worker;
import com.netflix.conductor.grpc.TaskServiceGrpc;
import com.netflix.conductor.grpc.TaskServicePb;
import com.netflix.conductor.proto.TaskPb;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Uninterruptibles;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PooledPoller implements StreamObserver<TaskPb.Task> {

    private final TaskServiceGrpc.TaskServiceStub asyncStub;
    private final Worker worker;
    private final String domain;
    private TaskUpdateObserver taskUpdateObserver;
    private final Integer taskPollTimeout;
    private ThreadPoolExecutor executor;
    private Integer threadCountForTask;
    private final ArrayBlockingQueue<Holder> latchesForOrder = new ArrayBlockingQueue<>(10000);
    private final AtomicBoolean runWorkers = new AtomicBoolean(true);
    private final AtomicBoolean callAgain = new AtomicBoolean(true);
    private final AtomicLong lastAskedForMessageCount = new AtomicLong(0);
    private final Semaphore semaphore;

    public PooledPoller(
            TaskServiceGrpc.TaskServiceStub asyncStub,
            Worker worker,
            String domain,
            TaskUpdateObserver taskUpdateObserver,
            Integer taskPollTimeout,
            ThreadPoolExecutor executor,
            Integer threadCountForTask,
            Semaphore semaphore) {
        this.asyncStub = asyncStub;
        this.worker = worker;
        this.domain = domain;
        this.taskUpdateObserver = taskUpdateObserver;
        this.taskPollTimeout = taskPollTimeout;
        this.executor = executor;
        this.threadCountForTask = threadCountForTask;
        this.semaphore = semaphore;
    }

    public void start() {
        Executors.newSingleThreadScheduledExecutor()
                .scheduleWithFixedDelay(
                        () -> {
                            try {
                                this.runAccumulatedRequests();
                            } catch (Exception e) {
                                log.warn("Unable to batch poll");
                            }
                        },
                        worker.getPollingInterval(),
                        worker.getPollingInterval(),
                        TimeUnit.MILLISECONDS);

        log.info("Starting {} threads for worker {}", threadCountForTask, worker.getTaskDefName());
        for (int i = 0; i < threadCountForTask; i++) {
            PoolWorker poolWorker =
                    new PoolWorker(this, worker, taskUpdateObserver, asyncStub, i, semaphore);
            executor.execute(
                    () -> {
                        try {
                            while (runWorkers.get()) {
                                try {
                                    poolWorker.run();
                                } catch (Exception e) {
                                    log.warn("Unable to run", e);
                                }
                            }
                        } finally {
                            log.error("DISASTER");
                        }
                    });
        }
    }

    public void stopWorkers() {
        runWorkers.set(false);
    }

    @Getter
    @Setter
    static class Holder {
        CountDownLatch myLatch;
        TaskPb.Task task;

        public Holder(CountDownLatch myLatch) {
            this.myLatch = myLatch;
        }
    }

    public TaskPb.Task getTask(int threadId) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        TaskPb.Task poll = null;
        try {
            CountDownLatch myLatch = new CountDownLatch(1);
            Holder holder = new Holder(myLatch);
            latchesForOrder.put(holder);
            Uninterruptibles.awaitUninterruptibly(myLatch);
            poll = holder.getTask();
        } catch (InterruptedException e) {
            log.error("ERROR WAITING --- ", e);
        } finally {
            long elapsed = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            if (elapsed > 9000) {
                log.info(
                        "Polled in {} ms - found task - {}",
                        elapsed,
                        (poll != null && !poll.getTaskId().equals("NO_OP")));
            }
        }
        if (poll == null) { // Poll shouldn't be null in a regular flow, only happens when we time
            // out on the blocking queue
            semaphore.release();
        }
        return poll;
    }

    public void saveTask(TaskPb.Task task) {
        if (task != null) {
            try {
                Holder holder = this.latchesForOrder.poll(1000, TimeUnit.MILLISECONDS);
                if (holder == null) {
                    throw new RuntimeException("Holder cannot be null!");
                }
                holder.task = task;
                holder.myLatch.countDown();
            } catch (InterruptedException e) {
                log.error("ERROR!", e);
            }
        }
    }

    public void runAccumulatedRequests() {
        int currentPending = this.threadCountForTask - semaphore.availablePermits();
        if (currentPending <= 0) {
            return;
        }
        // Make GRPC call for these many
        // Observe for results, add them to local queue
        if (callAgain.get()) {
            callAgain.set(false);
            lastAskedForMessageCount.set(currentPending);
            log.debug("Accumulated {} for {}", currentPending, worker.getTaskDefName());
            TaskServicePb.BatchPollRequest request = buildPollRequest(currentPending, 1);
            asyncStub.batchPoll(request, this);
        }
    }

    private TaskServicePb.BatchPollRequest buildPollRequest(int count, int timeoutInMillisecond) {
        TaskServicePb.BatchPollRequest.Builder requestBuilder =
                TaskServicePb.BatchPollRequest.newBuilder()
                        .setCount(count)
                        .setTaskType(worker.getTaskDefName())
                        .setTimeout(timeoutInMillisecond)
                        .setWorkerId(worker.getIdentity());
        if (domain != null) {
            requestBuilder = requestBuilder.setDomain(domain);
        }
        return requestBuilder.build();
    }

    @Override
    public void onNext(TaskPb.Task task) {
        try {
            saveTask(task);
            semaphore.release();
            lastAskedForMessageCount.decrementAndGet();
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
        }
    }

    @Override
    public void onError(Throwable t) {
        Status status = Status.fromThrowable(t);
        Status.Code code = status.getCode();
        drain();
        switch (code) {
            case UNAVAILABLE:
                log.trace("Server not available ");
                break;
            case UNAUTHENTICATED:
                log.error("{} - Invalid or missing api key/secret", code);
                break;
            case CANCELLED:
            case ABORTED:
            case DATA_LOSS:
            case DEADLINE_EXCEEDED:
                break;
            default:
                log.error("Error from server when polling for the task {} - {}", worker.getTaskDefName(), code);
        }
    }

    @Override
    public void onCompleted() {
        drain();
    }

    private void drain() {
        long didntGetMessageCount = lastAskedForMessageCount.get();
        if (didntGetMessageCount > 0) {
            log.debug("Didn't get {} messages from server as expected", didntGetMessageCount);
            for (int i = 0; i < didntGetMessageCount; i++) {
                this.saveTask(TaskPb.Task.newBuilder().setTaskId("NO_OP").build());
                semaphore.release();
            }
        }
        callAgain.set(true);
    }
}
