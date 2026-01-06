package org.jarvis.planner.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyPlanDto {
    private String userId;
    private LocalDate date;
    private Map<String, List<String>> blocks = new HashMap<>(); // morning, work, evening
    private List<TaskDto> tasksForDay = new ArrayList<>();
    private boolean confirmed;
    
    public void addBlock(String blockName, List<String> activities) {
        blocks.put(blockName, activities);
    }
}
