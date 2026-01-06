package org.jarvis.planner.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.DailyPlanDto;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.model.DailyPlan;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.repository.DailyPlanRepository;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyPlanGenerator {
    
    private final TaskRepository taskRepository;
    private final DailyPlanRepository dailyPlanRepository;
    private final ObjectMapper objectMapper;
    
    public DailyPlanDto generatePlan(String userId, LocalDate date) {
        log.info("Generating daily plan for user {} on {}", userId, date);
        
        DailyPlanDto plan = new DailyPlanDto();
        plan.setUserId(userId);
        plan.setDate(date);
        
        // Morning block
        List<String> morningActivities = new ArrayList<>();
        morningActivities.add("Подъём 07:00");
        morningActivities.add("Утренняя зарядка 15 мин");
        morningActivities.add("Завтрак 07:30");
        plan.addBlock("morning", morningActivities);
        
        // Work block
        List<String> workActivities = new ArrayList<>();
        workActivities.add("Рабочий день 09:00-17:00");
        plan.addBlock("work", workActivities);
        
        // Get active tasks
        List<Task> activeTasks = taskRepository.findActiveTasks(userId);
        List<TaskDto> topTasks = activeTasks.stream()
            .limit(5)
            .map(this::toDto)
            .toList();
        plan.setTasksForDay(topTasks);
        
        // Evening block
        List<String> eveningActivities = new ArrayList<>();
        eveningActivities.add("Ужин 19:00");
        eveningActivities.add("Личное время 20:00");
        eveningActivities.add("Подготовка ко сну 22:30");
        plan.addBlock("evening", eveningActivities);
        
        // Save plan
        savePlan(plan);
        
        return plan;
    }
    
    private void savePlan(DailyPlanDto dto) {
        try {
            DailyPlan entity = new DailyPlan();
            entity.setUserId(dto.getUserId());
            entity.setPlanDate(dto.getDate());
            entity.setPlanJson(objectMapper.writeValueAsString(dto));
            entity.setConfirmed(false);
            
            dailyPlanRepository.findByUserIdAndPlanDate(dto.getUserId(), dto.getDate())
                .ifPresentOrElse(
                    existing -> {
                        existing.setPlanJson(entity.getPlanJson());
                        dailyPlanRepository.save(existing);
                    },
                    () -> dailyPlanRepository.save(entity)
                );
        } catch (JsonProcessingException e) {
            log.error("Error saving plan: {}", e.getMessage());
        }
    }
    
    private TaskDto toDto(Task task) {
        TaskDto dto = new TaskDto();
        dto.setId(task.getId());
        dto.setUserId(task.getUserId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
        dto.setCategory(task.getCategory());
        dto.setPriority(task.getPriority());
        dto.setStatus(task.getStatus());
        dto.setDeadline(task.getDeadline());
        dto.setEstimatedDuration(task.getEstimatedDuration());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setCompletedAt(task.getCompletedAt());
        return dto;
    }
}
