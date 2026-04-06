package org.jarvis.userprofile.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.userprofile.model.CommunicationStyle;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanningContextDto {
    private String userId;
    private String displayName;
    private String timezone;
    private String language;
    private String occupation;
    private CommunicationStyle communicationStyle;
    private String favoriteBrowser;
    private String favoriteIde;
    private String favoriteMusicService;
    private List<UserGoalDto> goals = new ArrayList<>();
    private List<UserHabitDto> habits = new ArrayList<>();
    private List<UserPriorityDto> priorities = new ArrayList<>();
}
