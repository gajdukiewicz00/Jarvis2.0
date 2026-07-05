package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.metrics.PlannerMetrics;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 #10 — "reschedule when tired". When the user's energy is EXHAUSTED, push
 * hard / deep-work tasks out by a day so today only holds light work, and
 * explain the decision. Urgent tasks and already-overdue tasks are never
 * deferred — exhaustion is not a reason to dodge a fire that is already
 * burning; {@link RescheduleService} only trims tasks that can safely wait.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RescheduleService {

    private static final int DEFER_DAYS = 1;

    private final TaskRepository taskRepository;
    private final EnergyAwareRanker ranker;
    private final EnergyStateService energyStateService;
    private final PlannerMetrics plannerMetrics;

    @Transactional
    public Map<String, Object> rescheduleWhenTired(String userId, boolean force) {
        EnergyLevel energy = energyStateService.get(userId);
        boolean shouldReschedule = force || energy == EnergyLevel.EXHAUSTED;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("energy", energy.name());

        if (!shouldReschedule) {
            out.put("rescheduled", false);
            out.put("deferredCount", 0);
            out.put("deferredTasks", List.of());
            out.put("message", "Энергии достаточно, сэр — перепланировка не нужна.");
            return out;
        }

        plannerMetrics.reschedule(force ? "forced" : "exhausted");
        List<Task> deferred = deferHardTasks(userId);
        plannerMetrics.deferredTasks(deferred.size());

        out.put("rescheduled", true);
        out.put("deferredCount", deferred.size());
        out.put("deferredTasks", deferred.stream().map(this::toSummary).toList());
        out.put("message", deferred.isEmpty()
                ? "Тяжёлых задач для переноса не нашлось, сэр. Можно спокойно отдыхать."
                : "Перенёс на завтра " + deferred.size()
                        + " тяжёл(ую/ые) задач(у/и), сэр — сегодня только лёгкое или отдых.");
        return out;
    }

    private List<Task> deferHardTasks(String userId) {
        List<Task> active = taskRepository.findActiveTasks(userId);
        List<Task> deferred = new ArrayList<>();
        for (Task task : active) {
            if (!ranker.isHard(task) || isUrgentOrOverdue(task)) {
                continue;
            }
            if (task.getDueDate() != null) {
                task.setDueDate(task.getDueDate().plus(DEFER_DAYS, ChronoUnit.DAYS));
                taskRepository.save(task);
            }
            deferred.add(task);
        }
        return deferred;
    }

    private boolean isUrgentOrOverdue(Task task) {
        if (task.getPriority() == TaskPriority.URGENT) {
            return true;
        }
        Instant due = task.getDueDate();
        return due != null && due.isBefore(Instant.now());
    }

    private Map<String, Object> toSummary(Task task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.getId());
        m.put("title", task.getTitle());
        m.put("newDueDate", task.getDueDate());
        return m;
    }
}
