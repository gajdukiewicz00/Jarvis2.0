package org.jarvis.lifetracker.lifemap;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CrossServiceClientTest {

    private CrossServiceClient newClient(String plannerUrl, String visionUrl, String memoryUrl) {
        return new CrossServiceClient(plannerUrl, visionUrl, memoryUrl, 1500);
    }

    private MockRestServiceServer bindMockServer(CrossServiceClient client) {
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        return MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void fetchTasksReturnsParsedCountsOnSuccess() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo("http://planner/api/v1/planner/tasks/summary?userId=user-1"))
                .andRespond(withSuccess("{\"open\":3,\"doneToday\":5}", MediaType.APPLICATION_JSON));

        CrossServiceClient.Tasks tasks = client.fetchTasks("user-1");

        assertThat(tasks.open()).isEqualTo(3);
        assertThat(tasks.doneToday()).isEqualTo(5);
        server.verify();
    }

    @Test
    void fetchTasksReturnsEmptyWhenBodyIsNull() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo("http://planner/api/v1/planner/tasks/summary?userId=user-1"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        CrossServiceClient.Tasks tasks = client.fetchTasks("user-1");

        assertThat(tasks).isEqualTo(CrossServiceClient.Tasks.empty());
    }

    @Test
    void fetchTasksSafelyHandlesNullUserId() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo("http://planner/api/v1/planner/tasks/summary?userId="))
                .andRespond(withSuccess("{\"open\":1,\"doneToday\":1}", MediaType.APPLICATION_JSON));

        CrossServiceClient.Tasks tasks = client.fetchTasks(null);

        assertThat(tasks.open()).isEqualTo(1);
    }

    @Test
    void fetchTasksReturnsEmptyOnServerError() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo("http://planner/api/v1/planner/tasks/summary?userId=user-1"))
                .andRespond(withServerError());

        CrossServiceClient.Tasks tasks = client.fetchTasks("user-1");

        assertThat(tasks).isEqualTo(CrossServiceClient.Tasks.empty());
    }

    @Test
    void fetchTasksReturnsEmptyWhenHostUnreachable() {
        CrossServiceClient client = newClient("http://localhost:1", "http://vision", "http://memory");

        CrossServiceClient.Tasks tasks = client.fetchTasks("user-1");

        assertThat(tasks).isEqualTo(CrossServiceClient.Tasks.empty());
    }

    @Test
    void fetchVisionIncidentCountParsesNumericCount() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo("http://vision/api/v1/vision/incidents/count?userId=user-1&windowHours=24"))
                .andRespond(withSuccess("{\"count\":7}", MediaType.APPLICATION_JSON));

        int count = client.fetchVisionIncidentCount("user-1");

        assertThat(count).isEqualTo(7);
    }

    @Test
    void fetchVisionIncidentCountParsesStringCount() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo("http://vision/api/v1/vision/incidents/count?userId=user-1&windowHours=24"))
                .andRespond(withSuccess("{\"count\":\"12\"}", MediaType.APPLICATION_JSON));

        int count = client.fetchVisionIncidentCount("user-1");

        assertThat(count).isEqualTo(12);
    }

    @Test
    void fetchVisionIncidentCountReturnsZeroForUnparseableStringCount() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo("http://vision/api/v1/vision/incidents/count?userId=user-1&windowHours=24"))
                .andRespond(withSuccess("{\"count\":\"abc\"}", MediaType.APPLICATION_JSON));

        int count = client.fetchVisionIncidentCount("user-1");

        assertThat(count).isZero();
    }

    @Test
    void fetchVisionIncidentCountReturnsZeroWhenCountMissing() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo("http://vision/api/v1/vision/incidents/count?userId=user-1&windowHours=24"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        int count = client.fetchVisionIncidentCount("user-1");

        assertThat(count).isZero();
    }

    @Test
    void fetchMemoryWriteCountReturnsZeroBecauseSizeKeyIsNeverPopulated() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo(
                        "http://memory/api/v1/audit/events?userId=user-1&eventType=MEMORY_WRITTEN&limit=200"))
                .andRespond(withSuccess("[1,2,3]", MediaType.APPLICATION_JSON));

        int count = client.fetchMemoryWriteCount("user-1");

        assertThat(count).isZero();
    }

    @Test
    void fetchMemoryWriteCountReturnsZeroOnServerError() {
        CrossServiceClient client = newClient("http://planner", "http://vision", "http://memory");
        MockRestServiceServer server = bindMockServer(client);
        server.expect(requestTo(
                        "http://memory/api/v1/audit/events?userId=user-1&eventType=MEMORY_WRITTEN&limit=200"))
                .andRespond(withServerError());

        int count = client.fetchMemoryWriteCount("user-1");

        assertThat(count).isZero();
    }
}
