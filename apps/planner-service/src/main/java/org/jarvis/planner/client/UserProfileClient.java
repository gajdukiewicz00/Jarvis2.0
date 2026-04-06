package org.jarvis.planner.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Client for user-profile service
 */
@Slf4j
@Component
public class UserProfileClient {
    
    private final RestTemplate restTemplate;
    private final String userProfileUrl;
    
    public UserProfileClient(
            RestTemplate restTemplate,
            @Value("${services.user-profile.url}") String userProfileUrl
    ) {
        this.restTemplate = restTemplate;
        this.userProfileUrl = userProfileUrl;
    }
    
    public boolean isHealthy() {
        try {
            String url = userProfileUrl + "/actuator/health/readiness";
            restTemplate.getForEntity(url, String.class);
            return true;
        } catch (RestClientException e) {
            log.warn("user-profile health check failed: {}", e.getMessage());
            return false;
        }
    }

    public List<String> getUserGoals(String userId) {
        return getPlanningContext(userId).activeGoalTitles();
    }

    public PlanningContext getPlanningContext(String userId) {
        try {
            ResponseEntity<PlanningContextPayload> response = restTemplate.exchange(
                    userProfileUrl + "/api/v1/user-profile/{userId}/planning-context",
                    HttpMethod.GET,
                    requestEntity(userId),
                    PlanningContextPayload.class,
                    userId);

            PlanningContextPayload payload = response.getBody();
            if (payload == null) {
                return PlanningContext.empty();
            }

            return new PlanningContext(
                    payload.userId(),
                    payload.displayName(),
                    payload.timezone(),
                    payload.language(),
                    payload.goals() == null ? List.of() : Arrays.stream(payload.goals()).toList(),
                    payload.habits() == null ? List.of() : Arrays.stream(payload.habits()).toList(),
                    payload.priorities() == null ? List.of() : Arrays.stream(payload.priorities()).toList());
        } catch (RestClientException e) {
            log.warn("Failed to fetch planning context for {} from user-profile: {}", userId, e.getMessage());
            return PlanningContext.empty();
        }
    }

    private HttpEntity<Void> requestEntity(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        return new HttpEntity<>(headers);
    }

    public record PlanningContext(
            String userId,
            String displayName,
            String timezone,
            String language,
            List<UserGoalPayload> goals,
            List<UserHabitPayload> habits,
            List<UserPriorityPayload> priorities) {

        public static PlanningContext empty() {
            return new PlanningContext(null, null, null, null, List.of(), List.of(), List.of());
        }

        public List<String> activeGoalTitles() {
            return goals.stream()
                    .filter(goal -> goal.title() != null && !goal.title().isBlank())
                    .filter(UserGoalPayload::isActive)
                    .map(UserGoalPayload::title)
                    .toList();
        }

        public List<String> habitNamesForTime(String timeOfDay) {
            return habits.stream()
                    .filter(habit -> habit.name() != null && !habit.name().isBlank())
                    .filter(habit -> habit.matchesTime(timeOfDay))
                    .map(UserHabitPayload::name)
                    .distinct()
                    .toList();
        }

        public List<String> priorityCategories() {
            return priorities.stream()
                    .sorted((left, right) -> Integer.compare(
                            left.level() != null ? left.level() : Integer.MAX_VALUE,
                            right.level() != null ? right.level() : Integer.MAX_VALUE))
                    .map(UserPriorityPayload::name)
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        }
    }

    public record UserGoalPayload(String title, String status, String category, String targetDate, String deadline) {

        boolean isActive() {
            if (status == null || status.isBlank()) {
                return true;
            }
            String normalized = status.trim().toLowerCase(Locale.ROOT);
            return !normalized.equals("completed") && !normalized.equals("abandoned");
        }
    }

    public record UserHabitPayload(String name, String frequency, String timeOfDay) {

        boolean matchesTime(String desiredTime) {
            if (desiredTime == null || desiredTime.isBlank()) {
                return true;
            }
            if (timeOfDay == null || timeOfDay.isBlank()) {
                return false;
            }
            return desiredTime.trim().equalsIgnoreCase(timeOfDay.trim());
        }
    }

    public record UserPriorityPayload(String name, Integer level, String description) {
    }

    private record PlanningContextPayload(
            String userId,
            String displayName,
            String timezone,
            String language,
            UserGoalPayload[] goals,
            UserHabitPayload[] habits,
            UserPriorityPayload[] priorities) {
    }
}
