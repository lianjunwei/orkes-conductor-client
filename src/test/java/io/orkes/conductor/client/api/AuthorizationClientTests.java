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

import java.util.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.orkes.conductor.client.AuthorizationClient;
import io.orkes.conductor.client.http.ApiException;
import io.orkes.conductor.client.model.*;
import io.orkes.conductor.client.model.TargetRef.TypeEnum;
import io.orkes.conductor.client.model.UpsertGroupRequest.RolesEnum;
import io.orkes.conductor.client.util.Commons;

import static org.junit.jupiter.api.Assertions.*;

public class AuthorizationClientTests extends ClientTest {
    private final AuthorizationClient authorizationClient;

    public AuthorizationClientTests() {
        this.authorizationClient = super.orkesClients.getAuthorizationClient();
    }

    @Test
    @DisplayName("auto assign group permission on workflow creation by any group member")
    public void autoAssignWorkflowPermissions() {
        giveApplicationPermissions(Commons.APPLICATION_ID);
        Group group = authorizationClient.upsertGroup(getUpsertGroupRequest(), "sdk-test-group");
        validateGroupPermissions(group.getId());
    }

    @Test
    void testUser() {
        ConductorUser user =
                authorizationClient.upsertUser(getUpserUserRequest(), Commons.USER_EMAIL);
        ConductorUser receivedUser = authorizationClient.getUser(Commons.USER_EMAIL);
        assertEquals(user.getName(), receivedUser.getName());
        assertEquals(user.getGroups().get(0).getId(), receivedUser.getGroups().get(0).getId());
        assertEquals(user.getRoles().get(0).getName(), receivedUser.getRoles().get(0).getName());
        authorizationClient.sendInviteEmail(user.getId(), user);
        Group group = authorizationClient.upsertGroup(getUpsertGroupRequest(), Commons.GROUP_ID);
        assertNotNull(group);
        authorizationClient.removeUserFromGroup(Commons.GROUP_ID, user.getId());
        authorizationClient.removePermissions(getAuthorizationRequest());
    }

    @Test
    void testGroup() {
        UpsertGroupRequest request = new UpsertGroupRequest();

        // Default Access for the group. When specified, any new workflow or task
        // created by the
        // members of this group
        // get this default permission inside the group.
        Map<String, List<String>> defaultAccess = new HashMap<>();

        // Grant READ access to the members of the group for any new workflow created by
        // a member of
        // this group
        defaultAccess.put(TypeEnum.WORKFLOW_DEF.getValue(), List.of("READ"));

        // Grant EXECUTE access to the members of the group for any new task created by
        // a member of
        // this group
        defaultAccess.put(TypeEnum.TASK_DEF.getValue(), List.of("EXECUTE"));
        request.setDefaultAccess(defaultAccess);

        request.setDescription("Example group created for testing");
        request.setRoles(Arrays.asList(UpsertGroupRequest.RolesEnum.USER));

        Group group = authorizationClient.upsertGroup(request, Commons.GROUP_ID);
        assertNotNull(group);
        Group found = authorizationClient.getGroup(Commons.GROUP_ID);
        assertNotNull(found);
        assertEquals(group.getId(), found.getId());
        assertEquals(group.getDefaultAccess().keySet(), found.getDefaultAccess().keySet());
    }

    @Test
    void testApplication() {
        CreateOrUpdateApplicationRequest request = new CreateOrUpdateApplicationRequest();
        request.setName("Test Application for the testing");

        // WARNING: Application Name is not a UNIQUE value and if called multiple times,
        // it will
        // create a new application
        ConductorApplication application = authorizationClient.createApplication(request);
        assertNotNull(application);
        assertNotNull(application.getId());

        // Get the list of applications
        List<ConductorApplication> apps = authorizationClient.listApplications();
        assertNotNull(apps);
        long found =
                apps.stream()
                        .map(ConductorApplication::getId)
                        .filter(id -> id.equals(application.getId()))
                        .count();
        assertEquals(1, found);

        // Create new access key
        CreateAccessKeyResponse accessKey =
                authorizationClient.createAccessKey(application.getId());
        List<AccessKeyResponse> accessKeyResponses =
                authorizationClient.getAccessKeys(application.getId());
        assertEquals(1, accessKeyResponses.size());
        authorizationClient.toggleAccessKeyStatus(application.getId(), accessKey.getId());
        authorizationClient.deleteAccessKey(application.getId(), accessKey.getId());
        accessKeyResponses = authorizationClient.getAccessKeys(application.getId());
        assertEquals(0, accessKeyResponses.size());

        authorizationClient.removeRoleFromApplicationUser(
                application.getId(), RolesEnum.ADMIN.getValue());

        String newName = "ansdjansdjna";
        authorizationClient.updateApplication(
                new CreateOrUpdateApplicationRequest().name(newName), application.getId());
        assertEquals(newName, authorizationClient.getApplication(application.getId()).getName());

        authorizationClient.deleteApplication(application.getId());
    }

    @Test
    void testGrantPermissionsToGroup() {
        AuthorizationRequest request = new AuthorizationRequest();
        request.access(Arrays.asList(AuthorizationRequest.AccessEnum.READ));
        SubjectRef subject = new SubjectRef();
        subject.setId("Example Group");
        subject.setType(SubjectRef.TypeEnum.GROUP);
        request.setSubject(subject);
        TargetRef target = new TargetRef();
        target.setId("Test_032");
        target.setType(TargetRef.TypeEnum.WORKFLOW_DEF);
        request.setTarget(target);
        authorizationClient.grantPermissions(request);
    }

    @Test
    void testGrantPermissionsToTag() {
        authorizationClient.grantPermissions(getAuthorizationRequest());
    }

    @Test
    void testMethods() {
        try {
            authorizationClient.deleteUser(Commons.USER_EMAIL);
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }
        authorizationClient.upsertUser(getUpserUserRequest(), Commons.USER_EMAIL);
        List<ConductorUser> users = authorizationClient.listUsers(false);
        assertFalse(users.isEmpty());
        users = authorizationClient.listUsers(true);
        assertFalse(users.isEmpty());
        try {
            authorizationClient.deleteGroup(Commons.GROUP_ID);
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }
        authorizationClient.upsertGroup(getUpsertGroupRequest(), Commons.GROUP_ID);
        List<Group> groups = authorizationClient.listGroups();
        assertFalse(groups.isEmpty());
        authorizationClient.addUserToGroup(Commons.GROUP_ID, Commons.USER_EMAIL);
        boolean found = false;
        for (ConductorUser user : authorizationClient.getUsersInGroup(Commons.GROUP_ID)) {
            if (user.getName().equals(Commons.USER_NAME)) {
                found = true;
            }
        }
        assertTrue(found);
        authorizationClient.getPermissions("abc", Commons.GROUP_ID);
        assertEquals(
                authorizationClient.getApplication(Commons.APPLICATION_ID).getId(),
                Commons.APPLICATION_ID);
        assertTrue(
                authorizationClient
                        .getGrantedPermissionsForGroup(Commons.GROUP_ID)
                        .getGrantedAccess()
                        .isEmpty());
        assertFalse(
                authorizationClient
                        .getGrantedPermissionsForUser(Commons.USER_EMAIL)
                        .getGrantedAccess()
                        .isEmpty());
    }

    void giveApplicationPermissions(String applicationId) {
        authorizationClient.addRoleToApplicationUser(applicationId, RolesEnum.ADMIN.getValue());
    }

    void validateGroupPermissions(String id) {
        Group group = authorizationClient.getGroup(id);
        for (Map.Entry<String, List<String>> entry : group.getDefaultAccess().entrySet()) {
            List<String> expectedList = new ArrayList<>(getAccessListAll());
            List<String> actualList = new ArrayList<>(entry.getValue());
            Collections.sort(expectedList);
            Collections.sort(actualList);
            assertEquals(expectedList, actualList);
        }
    }

    UpsertGroupRequest getUpsertGroupRequest() {
        return new UpsertGroupRequest()
                .defaultAccess(
                        Map.of(
                                TypeEnum.WORKFLOW_DEF.getValue(), getAccessListAll(),
                                TypeEnum.TASK_DEF.getValue(), getAccessListAll()))
                .description("Group used for SDK testing")
                .roles(List.of(RolesEnum.ADMIN));
    }

    UpsertUserRequest getUpserUserRequest() {
        UpsertUserRequest request = new UpsertUserRequest();
        request.setName(Commons.USER_NAME);
        request.setGroups(List.of(Commons.GROUP_ID));
        request.setRoles(List.of(UpsertUserRequest.RolesEnum.USER));
        return request;
    }

    List<String> getAccessListAll() {
        return List.of("CREATE", "READ", "UPDATE", "EXECUTE", "DELETE");
    }

    AuthorizationRequest getAuthorizationRequest() {
        AuthorizationRequest request = new AuthorizationRequest();
        request.access(Arrays.asList(AuthorizationRequest.AccessEnum.READ));
        SubjectRef subject = new SubjectRef();
        subject.setId("Example Group");
        subject.setType(SubjectRef.TypeEnum.GROUP);
        request.setSubject(subject);
        TargetRef target = new TargetRef();
        target.setId("org:accounting");
        target.setType(TargetRef.TypeEnum.TAG);
        request.setTarget(target);
        return request;
    }
}
