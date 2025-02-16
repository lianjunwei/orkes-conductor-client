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
package io.orkes.conductor.client.api;

import org.junit.jupiter.api.Test;

import io.orkes.conductor.client.SchedulerClient;
import io.orkes.conductor.client.model.SaveScheduleRequest;
import io.orkes.conductor.client.model.WorkflowSchedule;
import io.orkes.conductor.client.util.Commons;

import static org.junit.jupiter.api.Assertions.*;

public class SchedulerClientTests extends ClientTest {
    private final String NAME = "test_sdk_java_scheduler_name";
    private final String CRON_EXPRESSION = "0 * * * * *";

    private final SchedulerClient schedulerClient;

    public SchedulerClientTests() {
        schedulerClient = super.orkesClients.getSchedulerClient();
    }

    @Test
    void testMethods() {
        schedulerClient.deleteSchedule(NAME);
        assertTrue(schedulerClient.getNextFewSchedules(CRON_EXPRESSION, 0L, 0L, 0).isEmpty());
        schedulerClient.saveSchedule(getSaveScheduleRequest());
        assertEquals(1, schedulerClient.getAllSchedules(Commons.WORKFLOW_NAME).size());
        WorkflowSchedule workflowSchedule = schedulerClient.getSchedule(NAME);
        assertEquals(NAME, workflowSchedule.getName());
        assertEquals(CRON_EXPRESSION, workflowSchedule.getCronExpression());
        assertFalse(schedulerClient.searchV22(0, 10, "ASC", "*", "").getResults().isEmpty());
        schedulerClient.pauseSchedule(NAME);
        workflowSchedule = schedulerClient.getSchedule(NAME);
        assertTrue(workflowSchedule.isPaused());
        schedulerClient.resumeSchedule(NAME);
        workflowSchedule = schedulerClient.getSchedule(NAME);
        assertFalse(workflowSchedule.isPaused());
        schedulerClient.deleteSchedule(NAME);
    }

    @Test
    void testDebugMethods() {
        schedulerClient.pauseAllSchedules();
        schedulerClient.resumeAllSchedules();
        schedulerClient.requeueAllExecutionRecords();
    }

    SaveScheduleRequest getSaveScheduleRequest() {
        return new SaveScheduleRequest()
                .name(NAME)
                .cronExpression(CRON_EXPRESSION)
                .startWorkflowRequest(Commons.getStartWorkflowRequest());
    }
}
