package org.jarvis.swarm.web;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.queue.AgentTaskService;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskControllerTest {

    private final AgentTaskService taskService = mock(AgentTaskService.class);
    private final SwarmFeatureGate gate = mock(SwarmFeatureGate.class);
    private final AgentTaskController controller = new AgentTaskController(taskService, gate);

    private AgentTask sampleTask;

    @BeforeEach
    void setUp() {
        sampleTask = SwarmTestFactory.task(AgentRole.CODER, "build a thing",
                Set.of(ToolPermission.WRITE_FILES), Set.of(ToolPermission.WRITE_FILES), true);
    }

    private MockHttpServletRequest requestWithUser(String userId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (userId != null) {
            req.addHeader("X-User-Id", userId);
        }
        return req;
    }

    @Test
    void createSubmitsTaskAndReturnsAccepted() {
        when(taskService.submit(eq("u1"), eq(AgentRole.CODER), eq("build a thing"),
                anySet(), eq(true), isNull(), isNull(), isNull())).thenReturn(sampleTask);
        when(taskService.resultOf(sampleTask.taskId())).thenReturn(null);
        CreateTaskRequest request = new CreateTaskRequest("CODER", "build a thing",
                List.of("WRITE_FILES"), true);

        ResponseEntity<TaskView> resp = controller.create(request, requestWithUser("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().taskId()).isEqualTo(sampleTask.taskId());
        assertThat(resp.getBody().goal()).isEqualTo("build a thing");
    }

    @Test
    void createForwardsClientIdempotencyKeyToTaskService() {
        when(taskService.submit(eq("u1"), eq(AgentRole.CODER), eq("build a thing"),
                anySet(), eq(true), isNull(), isNull(), eq("client-key-1"))).thenReturn(sampleTask);
        when(taskService.resultOf(sampleTask.taskId())).thenReturn(null);
        CreateTaskRequest request = new CreateTaskRequest("CODER", "build a thing",
                List.of("WRITE_FILES"), true, "client-key-1");

        ResponseEntity<TaskView> resp = controller.create(request, requestWithUser("u1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(taskService).submit(eq("u1"), eq(AgentRole.CODER), eq("build a thing"),
                anySet(), eq(true), isNull(), isNull(), eq("client-key-1"));
    }

    @Test
    void createThrowsUnauthenticatedWhenUserHeaderMissing() {
        CreateTaskRequest request = new CreateTaskRequest("CODER", "build a thing", List.of(), false);
        assertThatThrownBy(() -> controller.create(request, requestWithUser(null)))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void createThrowsSwarmDisabledWhenGateRejects() {
        doThrow(new SwarmDisabledException()).when(gate).ensureEnabled();
        CreateTaskRequest request = new CreateTaskRequest("CODER", "build a thing", List.of(), false);
        assertThatThrownBy(() -> controller.create(request, requestWithUser("u1")))
                .isInstanceOf(SwarmDisabledException.class);
    }

    @Test
    void listReturnsTaskViewsForOwner() {
        when(taskService.listTasks("u1")).thenReturn(List.of(sampleTask));
        when(taskService.resultOf(sampleTask.taskId())).thenReturn(null);

        List<TaskView> views = controller.list(requestWithUser("u1"));

        assertThat(views).hasSize(1);
        assertThat(views.get(0).taskId()).isEqualTo(sampleTask.taskId());
    }

    @Test
    void getReturnsTaskViewWithResult() {
        RoleResult result = RoleResult.success("done", "output", List.of(), List.of(), List.of(), List.of());
        when(taskService.getTask(sampleTask.taskId(), "u1")).thenReturn(sampleTask);
        when(taskService.resultOf(sampleTask.taskId())).thenReturn(result);

        TaskView view = controller.get(sampleTask.taskId(), requestWithUser("u1"));

        assertThat(view.result()).isEqualTo(result);
    }

    @Test
    void cancelInvokesServiceThenReturnsRefreshedView() {
        when(taskService.getTask(sampleTask.taskId(), "u1")).thenReturn(sampleTask);
        when(taskService.resultOf(sampleTask.taskId())).thenReturn(null);

        TaskView view = controller.cancel(sampleTask.taskId(), requestWithUser("u1"));

        verify(taskService).cancel(sampleTask.taskId(), "u1");
        assertThat(view.taskId()).isEqualTo(sampleTask.taskId());
    }

    @Test
    void pauseReturnsPausedView() {
        when(taskService.pause(sampleTask.taskId(), "u1")).thenReturn(sampleTask);
        when(taskService.resultOf(sampleTask.taskId())).thenReturn(null);

        TaskView view = controller.pause(sampleTask.taskId(), requestWithUser("u1"));

        assertThat(view.taskId()).isEqualTo(sampleTask.taskId());
    }

    @Test
    void resumeReturnsResumedView() {
        when(taskService.resume(sampleTask.taskId(), "u1")).thenReturn(sampleTask);
        when(taskService.resultOf(sampleTask.taskId())).thenReturn(null);

        TaskView view = controller.resume(sampleTask.taskId(), requestWithUser("u1"));

        assertThat(view.taskId()).isEqualTo(sampleTask.taskId());
    }
}
