package org.jarvis.userprofile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.userprofile.dto.UserPreferencesDto;
import org.jarvis.userprofile.model.CommunicationStyle;
import org.jarvis.userprofile.model.Emotion;
import org.jarvis.userprofile.model.UserPreferences;
import org.jarvis.userprofile.repository.UserPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Service for managing user preferences
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferencesService {
    
    private final UserPreferencesRepository repository;
    
    public Optional<UserPreferencesDto> getPreferences(String userId) {
        log.debug("Getting preferences for user: {}", userId);
        return repository.findByUserId(userId)
                .map(this::toDto);
    }
    
    @Transactional
    public UserPreferencesDto createOrUpdatePreferences(UserPreferencesDto dto) {
        log.info("Creating/updating preferences for user: {}", dto.getUserId());
        
        UserPreferences entity = repository.findByUserId(dto.getUserId())
                .map(existing -> updateEntity(existing, dto))
                .orElseGet(() -> toEntity(dto));
        
        UserPreferences saved = repository.save(entity);
        return toDto(saved);
    }
    
    @Transactional
    public UserPreferencesDto createDefaultPreferences(String userId) {
        log.info("Creating default preferences for user: {}", userId);
        
        if (repository.existsByUserId(userId)) {
            return toDto(repository.findByUserId(userId).get());
        }
        
        UserPreferences entity = new UserPreferences();
        entity.setUserId(userId);
        entity.setTimezone("Europe/Warsaw");
        entity.setLanguage("ru");
        entity.setCommunicationStyle(CommunicationStyle.FRIENDLY);
        entity.setAllowAutoAdaptation(true);
        entity.setAllowSarcasm(false);
        entity.setTtsVoiceId("jarvis_male_en");
        entity.setTtsEmotionDefault(Emotion.NEUTRAL);
        
        UserPreferences saved = repository.save(entity);
        return toDto(saved);
    }
    
    public boolean exists(String userId) {
        return repository.existsByUserId(userId);
    }
    
    @Transactional
    public void deletePreferences(String userId) {
        log.info("Deleting preferences for user: {}", userId);
        repository.findByUserId(userId)
                .ifPresent(repository::delete);
    }
    
    private UserPreferencesDto toDto(UserPreferences entity) {
        UserPreferencesDto dto = new UserPreferencesDto();
        dto.setUserId(entity.getUserId());
        dto.setFullName(entity.getFullName());
        dto.setTimezone(entity.getTimezone());
        dto.setLanguage(entity.getLanguage());
        dto.setOccupation(entity.getOccupation());
        dto.setCommunicationStyle(entity.getCommunicationStyle());
        dto.setAllowAutoAdaptation(entity.getAllowAutoAdaptation());
        dto.setAllowSarcasm(entity.getAllowSarcasm());
        dto.setTtsVoiceId(entity.getTtsVoiceId());
        dto.setTtsEmotionDefault(entity.getTtsEmotionDefault());
        dto.setFavoriteMusicService(entity.getFavoriteMusicService());
        dto.setFavoriteBrowser(entity.getFavoriteBrowser());
        dto.setFavoriteIde(entity.getFavoriteIde());
        return dto;
    }
    
    private UserPreferences toEntity(UserPreferencesDto dto) {
        UserPreferences entity = new UserPreferences();
        return updateEntity(entity, dto);
    }
    
    private UserPreferences updateEntity(UserPreferences entity, UserPreferencesDto dto) {
        entity.setUserId(dto.getUserId());
        entity.setFullName(dto.getFullName());
        entity.setTimezone(dto.getTimezone());
        entity.setLanguage(dto.getLanguage());
        entity.setOccupation(dto.getOccupation());
        entity.setCommunicationStyle(dto.getCommunicationStyle());
        entity.setAllowAutoAdaptation(dto.getAllowAutoAdaptation());
        entity.setAllowSarcasm(dto.getAllowSarcasm());
        entity.setTtsVoiceId(dto.getTtsVoiceId());
        entity.setTtsEmotionDefault(dto.getTtsEmotionDefault());
        entity.setFavoriteMusicService(dto.getFavoriteMusicService());
        entity.setFavoriteBrowser(dto.getFavoriteBrowser());
        entity.setFavoriteIde(dto.getFavoriteIde());
        return entity;
    }
}
