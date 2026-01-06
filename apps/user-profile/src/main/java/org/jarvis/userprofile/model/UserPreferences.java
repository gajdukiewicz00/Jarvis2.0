package org.jarvis.userprofile.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "user_preferences")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferences {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String userId;
    
    // Basic info
    private String fullName;
    private String timezone;
    private String language;
    private String occupation;
    
    // Communication style
    @Enumerated(EnumType.STRING)
    @Column(name = "communication_style")
    private CommunicationStyle communicationStyle;
    
    @Column(name = "allow_auto_adaptation")
    private Boolean allowAutoAdaptation;
    
    @Column(name = "allow_sarcasm")
    private Boolean allowSarcasm;
    
    // TTS preferences
    @Column(name = "tts_voice_id")
    private String ttsVoiceId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "tts_emotion_default")
    private Emotion ttsEmotionDefault;
    
    // Favorites
    @Column(name = "favorite_music_service")
    private String favoriteMusicService;
    
    @Column(name = "favorite_browser")
    private String favoriteBrowser;
    
    @Column(name = "favorite_ide")
    private String favoriteIde;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
