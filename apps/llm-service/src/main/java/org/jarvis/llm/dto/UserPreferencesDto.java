package org.jarvis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.llm.model.CommunicationStyle;
import org.jarvis.llm.model.Emotion;

/**
 * User preferences DTO from user-profile service
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferencesDto {
    private String userId;
    private String fullName;
    private String timezone;
    private String language;
    private String occupation;
    
    private CommunicationStyle communicationStyle;
    private Boolean allowAutoAdaptation;
    private Boolean allowSarcasm;
    
    private String ttsVoiceId;
    private Emotion ttsEmotionDefault;
    
    private String favoriteMusicService;
    private String favoriteBrowser;
    private String favoriteIde;
}
