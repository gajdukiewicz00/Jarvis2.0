package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.client.WellnessClient;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.WellnessSignal;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #12 — wellness-aware plan adjustment. Combines a {@link WellnessSignal}
 * (sleep/steps/energy, from {@link WellnessClient}) with the existing
 * {@link EnergyAwareRanker} and {@link EnergyStateService} to suggest a
 * {@link PlanMode} for the day and explain why. Deliberately reuses
 * {@link RescheduleService}'s existing "reschedule when tired" flow when the
 * suggested mode is {@link PlanMode#RECOVERY} instead of re-implementing
 * hard-task deferral here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanAdjustmentService {

    /** Nightly sleep below this is "very low sleep" — always suggests RECOVERY regardless of energy. */
    static final double VERY_LOW_SLEEP_HOURS = 5.0;
    /** Nightly sleep below this (but at/above the very-low floor) compounds with LOW energy into RECOVERY. */
    static final double MODERATE_SLEEP_HOURS = 7.0;

    private final WellnessClient wellnessClient;
    private final EnergyStateService energyStateService;
    private final EnergyAwareRanker ranker;
    private final TaskRepository taskRepository;
    private final RescheduleService rescheduleService;

    /** Wellness-adjusted day plan: suggested mode, ranked tasks, and a human explanation of why. */
    public Map<String, Object> getAdjustedPlan(String userId, boolean force) {
        WellnessSignal signal = fetchSignalSafely(userId);
        PlanMode mode = suggestMode(signal);
        EnergyLevel energy = energyStateService.get(userId);

        List<Task> ranked = ranker.rank(taskRepository.findActiveTasks(userId), energy, force, mode);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode.name());
        out.put("energy", energy.name());
        out.put("wellness", wellnessSummary(signal));
        out.put("tasks", ranked.stream().map(this::taskSummary).toList());
        out.put("explanation", explain(signal, mode));

        boolean rescheduleRecommended = mode == PlanMode.RECOVERY;
        out.put("rescheduleRecommended", rescheduleRecommended);
        if (rescheduleRecommended) {
            // Reuse the existing voice-friendly "reschedule when tired" flow rather
            // than re-implementing hard-task deferral here.
            out.put("reschedule", rescheduleService.rescheduleWhenTired(userId, force));
        }
        return out;
    }

    /** Best-effort wellness signal fetch — never throws, always falls back to a neutral default. */
    private WellnessSignal fetchSignalSafely(String userId) {
        try {
            WellnessSignal signal = wellnessClient.fetchSignal(userId);
            return signal == null ? WellnessSignal.neutralDefault() : signal;
        } catch (RuntimeException e) {
            log.warn("wellness client failed for {}, falling back to neutral signal: {}", userId, e.getMessage());
            return WellnessSignal.neutralDefault();
        }
    }

    /** Maps a wellness signal to a suggested {@link PlanMode}, most urgent rule first. */
    PlanMode suggestMode(WellnessSignal signal) {
        if (signal == null) {
            return PlanMode.NORMAL;
        }
        if (signal.sleepHours() != null && signal.sleepHours() < VERY_LOW_SLEEP_HOURS) {
            return PlanMode.RECOVERY; // very low sleep always wins, regardless of energy
        }
        EnergyLevel energy = signal.energy() == null ? EnergyLevel.NORMAL : signal.energy();
        if (energy == EnergyLevel.EXHAUSTED) {
            return PlanMode.RECOVERY;
        }
        if (energy == EnergyLevel.LOW && signal.sleepHours() != null && signal.sleepHours() < MODERATE_SLEEP_HOURS) {
            return PlanMode.RECOVERY; // low energy compounded by short sleep
        }
        if (energy == EnergyLevel.HIGH) {
            return PlanMode.DEEP_WORK;
        }
        return PlanMode.NORMAL;
    }

    private String explain(WellnessSignal signal, PlanMode mode) {
        String sleepPart = signal.sleepHours() == null
                ? "данных о сне нет" : String.format("сон ~%.1f ч.", signal.sleepHours());
        String stepsPart = signal.steps() == null ? "шаги неизвестны" : signal.steps() + " шагов";
        EnergyLevel energy = signal.energy() == null ? EnergyLevel.NORMAL : signal.energy();
        String basis = sleepPart + ", " + stepsPart + ", энергия " + energy.name();
        return switch (mode) {
            case RECOVERY -> "Режим восстановления, сэр (" + basis + ") — сегодня только лёгкое, тяжёлое отложим.";
            case DEEP_WORK -> "Режим глубокой работы, сэр (" + basis + ") — самое время для серьёзной задачи.";
            default -> "Обычный день, сэр (" + basis + ") — план по приоритету и энергии.";
        };
    }

    private Map<String, Object> wellnessSummary(WellnessSignal signal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sleepHours", signal.sleepHours());
        m.put("steps", signal.steps());
        m.put("energy", (signal.energy() == null ? EnergyLevel.NORMAL : signal.energy()).name());
        return m;
    }

    private Map<String, Object> taskSummary(Task task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.getId());
        m.put("title", task.getTitle());
        m.put("priority", task.getPriority());
        m.put("estimatedDuration", task.getEstimatedDuration());
        m.put("dueDate", task.getDueDate());
        m.put("deadlinePressure", ranker.deadlineLabel(task));
        return m;
    }
}
