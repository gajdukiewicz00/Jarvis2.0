package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Generates weekly plans by distributing tasks across the week
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyPlanGenerator {
    
    private final TaskRepository taskRepository;
    
    public Map<String, Object> generateWeeklyPlan(String userId) {
        log.info("Generating weekly plan for user: {}", userId);
        
        Map<String, Object> weeklyPlan = new HashMap<>();
        weeklyPlan.put("userId", userId);
        weeklyPlan.put("weekStart", LocalDate.now());
        
        // Get active tasks
        List<Task> tasks = taskRepository.findActiveTasks(userId);
        
        // Distribute across days
        Map<String, List<String>> dayPlans = new LinkedHashMap<>();
        String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
        
        int tasksPerDay = Math.max(1, tasks.size() / 7);
        int taskIndex = 0;
        
        for (String day : days) {
            List<String> dayTasks = new ArrayList<>();
            for (int i = 0; i < tasksPerDay && taskIndex < tasks.size(); i++) {
                dayTasks.add(tasks.get(taskIndex++).getTitle());
            }
            if (!dayTasks.isEmpty()) {
                dayPlans.put(day, dayTasks);
            }
        }
        
        weeklyPlan.put("days", dayPlans);
        weeklyPlan.put("totalTasks", tasks.size());
        
        return weeklyPlan;
    }
}
