package org.jarvis.planner.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserProfileClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private UserProfileClient userProfileClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        userProfileClient = new UserProfileClient(restTemplate, "http://user-profile:8089");
    }

    @Test
    void getPlanningContextFetchesUserScopedProfileData() {
        server.expect(requestTo("http://user-profile:8089/api/v1/user-profile/user-123/planning-context"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-Id", "user-123"))
                .andRespond(withSuccess("""
                        {
                          "userId":"user-123",
                          "timezone":"Europe/Warsaw",
                          "language":"ru",
                          "goals":[
                            {"title":"Ship core backend","status":"active"},
                            {"title":"Reduce infra drift","status":"completed"}
                          ],
                          "habits":[
                            {"name":"Morning review","timeOfDay":"morning"},
                            {"name":"Shutdown checklist","timeOfDay":"evening"}
                          ],
                          "priorities":[
                            {"name":"Backend","level":1},
                            {"name":"Health","level":2}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        UserProfileClient.PlanningContext context = userProfileClient.getPlanningContext("user-123");

        assertEquals(List.of("Ship core backend"), context.activeGoalTitles());
        assertEquals(List.of("Morning review"), context.habitNamesForTime("morning"));
        assertEquals(List.of("Backend", "Health"), context.priorityCategories());
        server.verify();
    }
}
