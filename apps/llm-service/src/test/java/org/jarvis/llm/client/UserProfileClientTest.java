package org.jarvis.llm.client;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.llm.dto.UserPreferencesDto;
import org.jarvis.llm.model.CommunicationStyle;
import org.jarvis.llm.model.Emotion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserProfileClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ServiceJwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        jwtProvider = mock(ServiceJwtProvider.class);
        when(jwtProvider.createToken(anyString(), anyList())).thenReturn("svc-token");
    }

    private UserProfileClient client(boolean enabled) {
        return new UserProfileClient(restTemplate, "http://user-profile:8089", enabled, jwtProvider, "llm-service");
    }

    @Test
    void isEnabledReflectsConstructorFlag() {
        assertThat(client(true).isEnabled()).isTrue();
        assertThat(client(false).isEnabled()).isFalse();
    }

    @Test
    void getPreferencesReturnsDefaultsWhenDisabled() {
        UserProfileClient client = client(false);

        UserPreferencesDto prefs = client.getPreferences("user1", "corr-1");

        assertThat(prefs.getUserId()).isEqualTo("user1");
        assertThat(prefs.getFullName()).isEqualTo("User");
        assertThat(prefs.getLanguage()).isEqualTo("ru");
        assertThat(prefs.getCommunicationStyle()).isEqualTo(CommunicationStyle.FRIENDLY);
        assertThat(prefs.getTtsEmotionDefault()).isEqualTo(Emotion.NEUTRAL);
    }

    @Test
    void getPreferencesFetchesFromServiceWhenEnabled() {
        UserProfileClient client = client(true);

        server.expect(requestTo("http://user-profile:8089/api/v1/profile/preferences/user1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "userId": "user1",
                          "fullName": "Denis",
                          "timezone": "Europe/Warsaw",
                          "language": "ru",
                          "occupation": "engineer",
                          "communicationStyle": "FRIENDLY",
                          "allowAutoAdaptation": true,
                          "allowSarcasm": true,
                          "ttsVoiceId": "jarvis_male_en",
                          "ttsEmotionDefault": "NEUTRAL"
                        }
                        """, MediaType.APPLICATION_JSON));

        UserPreferencesDto prefs = client.getPreferences("user1", "corr-2");

        assertThat(prefs.getFullName()).isEqualTo("Denis");
        assertThat(prefs.getAllowSarcasm()).isTrue();
        server.verify();
    }

    @Test
    void getPreferencesReturnsDefaultsOnServerError() {
        UserProfileClient client = client(true);

        server.expect(requestTo("http://user-profile:8089/api/v1/profile/preferences/user1"))
                .andRespond(withServerError());

        UserPreferencesDto prefs = client.getPreferences("user1", "corr-3");

        assertThat(prefs.getFullName()).isEqualTo("User");
        assertThat(prefs.getUserId()).isEqualTo("user1");
    }

    @Test
    void getPreferencesOverloadUsesNoCorrelationId() {
        UserProfileClient client = client(false);

        UserPreferencesDto prefs = client.getPreferences("user1");

        assertThat(prefs.getUserId()).isEqualTo("user1");
    }

    @Test
    void getGoalsReturnsEmptyListWhenDisabled() {
        UserProfileClient client = client(false);

        List<String> goals = client.getGoals("user1", "corr-4");

        assertThat(goals).isEmpty();
    }

    @Test
    void getGoalsReturnsTitlesFromService() {
        UserProfileClient client = client(true);

        server.expect(requestTo("http://user-profile:8089/api/v1/user-profile/user1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"title": "Ship Jarvis"},
                          {"title": ""},
                          {"title": null}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<String> goals = client.getGoals("user1", "corr-5");

        assertThat(goals).containsExactly("Ship Jarvis");
        server.verify();
    }

    @Test
    void getGoalsReturnsEmptyListOnServerError() {
        UserProfileClient client = client(true);

        server.expect(requestTo("http://user-profile:8089/api/v1/user-profile/user1/goals"))
                .andRespond(withServerError());

        List<String> goals = client.getGoals("user1", "corr-6");

        assertThat(goals).isEmpty();
    }
}
