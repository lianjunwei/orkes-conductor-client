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
package io.orkes.conductor.sdk.examples;

import java.util.List;

import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;

import io.orkes.conductor.client.OrkesClients;
import io.orkes.conductor.client.SchedulerClient;
import io.orkes.conductor.client.model.SaveScheduleRequest;
import io.orkes.conductor.client.model.WorkflowSchedule;

import static io.orkes.conductor.sdk.examples.MetadataManagement.workflowDef;

/**
 * Examples for managing Schedules in Orkes Conductor
 *
 * <p>1. saveSchedule - Create workflow schedule 2. pauseSchedule - Pause workflow schedule 3.
 * getSchedule - Get schedule for workflow 4. getNextFewSchedules - Get next few schedule runs for
 * schedule 5. pauseAllSchedules - Pause all the schedules 6. resumeAllSchedules - Resume all the
 * schedules 7. searchV2 - Get Schedule executions
 */
public class SchedulerManagement {

    private static SchedulerClient schedulerClient;

    private static String scheduleName = "sample_schedule";
    public static final long NANO = 1_000_000_000; // nano-seconds.
    String cron = "0 0 * ? * *";

    public static void main(String[] args) {
        OrkesClients orkesClients = ApiUtil.getOrkesClient();
        createMetadata();
        SchedulerManagement schedulerManagement = new SchedulerManagement();
        schedulerClient = orkesClients.getSchedulerClient();
        schedulerManagement.createSchedule();
        schedulerManagement.scheduleOperations();
    }

    private static void createMetadata() {
        MetadataManagement metadataManagement = new MetadataManagement();
        metadataManagement.createTaskDefinitions();
        metadataManagement.createWorkflowDefinitions();
    }

    private void createSchedule() {
        // Create save schedule request
        SaveScheduleRequest saveScheduleRequest = new SaveScheduleRequest();
        saveScheduleRequest.createdBy("test@orkes.io");
        saveScheduleRequest.cronExpression(cron);
        saveScheduleRequest.setName(scheduleName);
        // Create start workflow request
        StartWorkflowRequest startWorkflowRequest = new StartWorkflowRequest();
        startWorkflowRequest.setName(workflowDef.getName());
        startWorkflowRequest.setVersion(workflowDef.getVersion());
        startWorkflowRequest.setCorrelationId("testing");
        saveScheduleRequest.setStartWorkflowRequest(startWorkflowRequest);

        // Save schedule
        schedulerClient.saveSchedule(saveScheduleRequest);

        // Verify that schedule is saved
        WorkflowSchedule workflowSchedule = schedulerClient.getSchedule(scheduleName);
        cron.equals(workflowSchedule.getCronExpression());
    }

    private void scheduleOperations() {
        // Pause Schedule
        schedulerClient.pauseSchedule(scheduleName);

        // Verify the schedule is paused
        WorkflowSchedule workflowSchedule = schedulerClient.getSchedule(scheduleName);
        System.out.println(workflowSchedule.isPaused() == true);

        /// Resume schedule
        schedulerClient.resumeSchedule(scheduleName);
        // Verify the schedule is resumed
        WorkflowSchedule workflowSchedule1 = schedulerClient.getSchedule(scheduleName);
        System.out.println(workflowSchedule.isPaused() == false);

        // Example to get schedule, pause, resume, find next schedules, list scheduled executions

        // Find the next run
        List<Long> schedules =
                schedulerClient.getNextFewSchedules(
                        cron, System.nanoTime(), System.nanoTime() + 6 * 60 * 60 * NANO, 5);
        System.out.println(schedules.size() == 5);

        // Pause all schedules
        schedulerClient.pauseAllSchedules();
        /// Resume all schedule
        schedulerClient.resumeAllSchedules();

        // Get Scheduled executions
        // TODO
    }
}
