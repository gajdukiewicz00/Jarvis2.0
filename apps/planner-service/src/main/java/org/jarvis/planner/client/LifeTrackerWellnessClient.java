package org.jarvis.planner.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.WellnessSignal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Default {@link WellnessClient} — fetches the user's latest sleep/steps
 * snapshot from life-tracker's wellness summary endpoint
 * ({@code GET /api/v1/life/wellness/summary}) and derives a best-effort
 * {@link EnergyLevel} estimate from them. Degrades gracefully to
 * {@link WellnessSignal#neutralDefault()} whenever life-tracker is
 * unreachable or returns nothing usable — never throws.
 */
@Slf4j
@Component
public class LifeTrackerWellnessClient implements WellnessClient {

    /** Below this nightly sleep, the derived energy estimate drops to EXHAUSTED. */
    static final double EXHAUSTED_SLEEP_HOURS = 5.0;
    /** Below this nightly sleep (but at/above the exhausted floor), energy drops to LOW. */
    static final double LOW_SLEEP_HOURS = 6.5;
    /** Step count at/above which activity alone can nudge energy up to HIGH. */
    static final int HIGH_STEP_COUNT = 8_000;

    private final RestTemplate restTemplate;
    private final String lifeTrackerUrl;

    public LifeTrackerWellnessClient(
            RestTemplate restTemplate,
            @Value("${services.life-tracker.url}") String lifeTrackerUrl) {
        this.restTemplate = restTemplate;
        this.lifeTrackerUrl = lifeTrackerUrl;
    }

    @Override
    public WellnessSignal fetchSignal(String userId) {
        try {
            Double sleepHours = fetchLatest(userId, "SLEEP");
            Integer steps = toInt(fetchLatest(userId, "STEPS"));
            return new WellnessSignal(sleepHours, steps, deriveEnergy(sleepHours, steps));
        } catch (RuntimeException e) {
            log.warn("Failed to fetch wellness signal for {} from life-tracker, using neutral default: {}",
                    userId, e.getMessage());
            return WellnessSignal.neutralDefault();
        }
    }

    private Double fetchLatest(String userId, String type) {
        ResponseEntity<WellnessSummaryPayload> response = restTemplate.exchange(
                lifeTrackerUrl + "/api/v1/life/wellness/summary?type={type}",
                HttpMethod.GET,
                requestEntity(userId),
                WellnessSummaryPayload.class,
                type);
        WellnessSummaryPayload body = response.getBody();
        return body == null ? null : body.latest();
    }

    private Integer toInt(Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    private EnergyLevel deriveEnergy(Double sleepHours, Integer steps) {
        if (sleepHours != null && sleepHours < EXHAUSTED_SLEEP_HOURS) {
            return EnergyLevel.EXHAUSTED;
        }
        if (sleepHours != null && sleepHours < LOW_SLEEP_HOURS) {
            return EnergyLevel.LOW;
        }
        if (steps != null && steps >= HIGH_STEP_COUNT && (sleepHours == null || sleepHours >= LOW_SLEEP_HOURS)) {
            return EnergyLevel.HIGH;
        }
        return EnergyLevel.NORMAL;
    }

    private HttpEntity<Void> requestEntity(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        return new HttpEntity<>(headers);
    }

    /** Subset of life-tracker's WellnessSummaryDTO needed here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WellnessSummaryPayload(Double latest) {
    }
}
