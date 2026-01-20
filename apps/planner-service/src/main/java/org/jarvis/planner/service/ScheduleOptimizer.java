package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Optimizes task schedule to avoid conflicts and balance workload
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleOptimizer {
    
    private final TaskRepository taskRepository;
    
    /**
     * Distribute tasks across available time slots
     */
    public Map<String, List<Task>> optimizeSchedule(String userId, LocalDate startDate, int days) {
        log.info("Optimizing schedule for user: {} from {} for {} days", userId, startDate, days);
        
        List<Task> activeTasks = taskRepository.findActiveTasks(userId);
        
        // Sort by priority and due date
        List<Task> sortedTasks = activeTasks.stream()
            .sorted(Comparator
                .comparing(Task::getPriority, Comparator.reverseOrder())
                .thenComparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
            .collect(Collectors.toList());
        
        // Distribute across days
        Map<String, List<Task>> schedule = new LinkedHashMap<>();
        int tasksPerDay = Math.max(1, sortedTasks.size() / days);
        
        for (int day = 0; day < days; day++) {
            LocalDate date = startDate.plusDays(day);
            String dateKey = date.toString();
            
            List<Task> dayTasks = new ArrayList<>();
            int startIndex = day * tasksPerDay;
            int endIndex = Math.min(startIndex + tasksPerDay, sortedTasks.size());
            
            for (int i = startIndex; i < endIndex; i++) {
                dayTasks.add(sortedTasks.get(i));
            }
            
            if (!dayTasks.isEmpty()) {
                schedule.put(dateKey, dayTasks);
            }
        }
        
        return schedule;
    }
    
    /**
     * Calculate total estimated time for tasks
     */
    public int calculateTotalDuration(List<Task> tasks) {
        return tasks.stream()
            .map(Task::getEstimatedDuration)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .sum();
    }
}
