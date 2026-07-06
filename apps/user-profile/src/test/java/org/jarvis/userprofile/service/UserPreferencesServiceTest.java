package org.jarvis.userprofile.service;

import org.jarvis.userprofile.dto.UserPreferencesDto;
import org.jarvis.userprofile.model.CommunicationStyle;
import org.jarvis.userprofile.model.Emotion;
import org.jarvis.userprofile.model.UserPreferences;
import org.jarvis.userprofile.repository.UserPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPreferencesServiceTest {

    @Mock
    private UserPreferencesRepository repository;

    private UserPreferencesService service;

    @BeforeEach
    void setUp() {
        service = new UserPreferencesService(repository);
    }

    @Test
    void getPreferencesReturnsMappedDtoWhenFound() {
        UserPreferences entity = new UserPreferences();
        entity.setUserId("user-1");
        entity.setFullName("Denis");
        entity.setTimezone("Europe/Warsaw");
        entity.setLanguage("ru");
        entity.setOccupation("Engineer");
        entity.setCommunicationStyle(CommunicationStyle.FRIENDLY);
        entity.setAllowAutoAdaptation(true);
        entity.setAllowSarcasm(false);
        entity.setTtsVoiceId("jarvis_male_en");
        entity.setTtsEmotionDefault(Emotion.CALM);
        entity.setFavoriteMusicService("Spotify");
        entity.setFavoriteBrowser("Firefox");
        entity.setFavoriteIde("IntelliJ IDEA");

        when(repository.findByUserId("user-1")).thenReturn(Optional.of(entity));

        Optional<UserPreferencesDto> result = service.getPreferences("user-1");

        assertTrue(result.isPresent());
        UserPreferencesDto dto = result.get();
        assertEquals("user-1", dto.getUserId());
        assertEquals("Denis", dto.getFullName());
        assertEquals("Europe/Warsaw", dto.getTimezone());
        assertEquals("ru", dto.getLanguage());
        assertEquals("Engineer", dto.getOccupation());
        assertEquals(CommunicationStyle.FRIENDLY, dto.getCommunicationStyle());
        assertTrue(dto.getAllowAutoAdaptation());
        assertFalse(dto.getAllowSarcasm());
        assertEquals("jarvis_male_en", dto.getTtsVoiceId());
        assertEquals(Emotion.CALM, dto.getTtsEmotionDefault());
        assertEquals("Spotify", dto.getFavoriteMusicService());
        assertEquals("Firefox", dto.getFavoriteBrowser());
        assertEquals("IntelliJ IDEA", dto.getFavoriteIde());
    }

    @Test
    void getPreferencesReturnsEmptyWhenNotFound() {
        when(repository.findByUserId("missing")).thenReturn(Optional.empty());

        Optional<UserPreferencesDto> result = service.getPreferences("missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void createOrUpdatePreferencesCreatesNewEntityWhenNoneExists() {
        UserPreferencesDto dto = new UserPreferencesDto();
        dto.setUserId("user-2");
        dto.setFullName("New User");
        dto.setTimezone("UTC");
        dto.setLanguage("en");
        dto.setCommunicationStyle(CommunicationStyle.CONCISE);
        dto.setAllowAutoAdaptation(false);
        dto.setAllowSarcasm(true);
        dto.setTtsVoiceId("voice-x");
        dto.setTtsEmotionDefault(Emotion.ENERGETIC);
        dto.setFavoriteMusicService("YouTube Music");
        dto.setFavoriteBrowser("Chrome");
        dto.setFavoriteIde("VSCode");

        when(repository.findByUserId("user-2")).thenReturn(Optional.empty());
        when(repository.save(any(UserPreferences.class))).thenAnswer(inv -> inv.getArgument(0));

        UserPreferencesDto result = service.createOrUpdatePreferences(dto);

        ArgumentCaptor<UserPreferences> captor = ArgumentCaptor.forClass(UserPreferences.class);
        verify(repository).save(captor.capture());
        UserPreferences saved = captor.getValue();
        assertNull(saved.getId());
        assertEquals("user-2", saved.getUserId());
        assertEquals("New User", saved.getFullName());
        assertEquals(CommunicationStyle.CONCISE, saved.getCommunicationStyle());

        assertEquals("user-2", result.getUserId());
        assertEquals("VSCode", result.getFavoriteIde());
        assertEquals(Emotion.ENERGETIC, result.getTtsEmotionDefault());
    }

    @Test
    void createOrUpdatePreferencesUpdatesExistingEntityInPlace() {
        UserPreferences existing = new UserPreferences();
        existing.setId(42L);
        existing.setUserId("user-3");
        existing.setFullName("Old Name");
        existing.setTimezone("UTC");

        UserPreferencesDto dto = new UserPreferencesDto();
        dto.setUserId("user-3");
        dto.setFullName("Updated Name");
        dto.setTimezone("Europe/Warsaw");
        dto.setLanguage("ru");
        dto.setCommunicationStyle(CommunicationStyle.FORMAL);
        dto.setAllowAutoAdaptation(true);
        dto.setAllowSarcasm(false);
        dto.setTtsVoiceId("voice-y");
        dto.setTtsEmotionDefault(Emotion.EMPATHETIC);
        dto.setFavoriteMusicService("Apple Music");
        dto.setFavoriteBrowser("Safari");
        dto.setFavoriteIde("Xcode");

        when(repository.findByUserId("user-3")).thenReturn(Optional.of(existing));
        when(repository.save(any(UserPreferences.class))).thenAnswer(inv -> inv.getArgument(0));

        UserPreferencesDto result = service.createOrUpdatePreferences(dto);

        ArgumentCaptor<UserPreferences> captor = ArgumentCaptor.forClass(UserPreferences.class);
        verify(repository).save(captor.capture());
        UserPreferences saved = captor.getValue();
        assertEquals(42L, saved.getId());
        assertEquals("Updated Name", saved.getFullName());
        assertEquals("Europe/Warsaw", saved.getTimezone());

        assertEquals("Updated Name", result.getFullName());
        assertEquals(Emotion.EMPATHETIC, result.getTtsEmotionDefault());
    }

    @Test
    void createDefaultPreferencesReturnsExistingWhenAlreadyPresent() {
        UserPreferences existing = new UserPreferences();
        existing.setUserId("user-4");
        existing.setFullName("Existing");
        existing.setTimezone("America/New_York");

        when(repository.existsByUserId("user-4")).thenReturn(true);
        when(repository.findByUserId("user-4")).thenReturn(Optional.of(existing));

        UserPreferencesDto result = service.createDefaultPreferences("user-4");

        assertEquals("user-4", result.getUserId());
        assertEquals("America/New_York", result.getTimezone());
        verify(repository, never()).save(any());
    }

    @Test
    void createDefaultPreferencesCreatesDefaultsWhenMissing() {
        when(repository.existsByUserId("user-5")).thenReturn(false);
        when(repository.save(any(UserPreferences.class))).thenAnswer(inv -> inv.getArgument(0));

        UserPreferencesDto result = service.createDefaultPreferences("user-5");

        ArgumentCaptor<UserPreferences> captor = ArgumentCaptor.forClass(UserPreferences.class);
        verify(repository).save(captor.capture());
        UserPreferences saved = captor.getValue();
        assertEquals("user-5", saved.getUserId());
        assertEquals("Europe/Warsaw", saved.getTimezone());
        assertEquals("ru", saved.getLanguage());
        assertEquals(CommunicationStyle.FRIENDLY, saved.getCommunicationStyle());
        assertTrue(saved.getAllowAutoAdaptation());
        assertFalse(saved.getAllowSarcasm());
        assertEquals("jarvis_male_en", saved.getTtsVoiceId());
        assertEquals(Emotion.NEUTRAL, saved.getTtsEmotionDefault());

        assertEquals("user-5", result.getUserId());
        assertEquals(Emotion.NEUTRAL, result.getTtsEmotionDefault());
    }

    @Test
    void createDefaultPreferencesIsIdempotentWhenConcurrentInsertWinsRace() {
        // Simulates two concurrent first-time GETs for a user with no
        // preferences row yet: both see existsByUserId() == false, both
        // attempt the INSERT, and the loser hits the unique-constraint
        // violation. The loser must catch it and return the row the winner
        // already created instead of letting a raw 500 propagate.
        when(repository.existsByUserId("user-9")).thenReturn(false);
        when(repository.save(any(UserPreferences.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        UserPreferences alreadyCreated = new UserPreferences();
        alreadyCreated.setUserId("user-9");
        alreadyCreated.setTimezone("Europe/Warsaw");
        alreadyCreated.setLanguage("ru");
        alreadyCreated.setCommunicationStyle(CommunicationStyle.FRIENDLY);
        when(repository.findByUserId("user-9")).thenReturn(Optional.of(alreadyCreated));

        UserPreferencesDto result = service.createDefaultPreferences("user-9");

        assertEquals("user-9", result.getUserId());
        assertEquals("Europe/Warsaw", result.getTimezone());
        assertEquals(CommunicationStyle.FRIENDLY, result.getCommunicationStyle());
    }

    @Test
    void createDefaultPreferencesRethrowsWhenConstraintViolationIsNotARace() {
        // If no row exists for this user after the violation, this was some
        // other integrity issue, not a benign concurrent-insert race, and
        // must still surface to the caller.
        when(repository.existsByUserId("user-10")).thenReturn(false);
        when(repository.save(any(UserPreferences.class)))
                .thenThrow(new DataIntegrityViolationException("some other violation"));
        when(repository.findByUserId("user-10")).thenReturn(Optional.empty());

        assertThrows(DataIntegrityViolationException.class,
                () -> service.createDefaultPreferences("user-10"));
    }

    @Test
    void existsDelegatesToRepository() {
        when(repository.existsByUserId("user-6")).thenReturn(true);

        assertTrue(service.exists("user-6"));
        verify(repository).existsByUserId("user-6");
    }

    @Test
    void deletePreferencesDeletesWhenPresent() {
        UserPreferences existing = new UserPreferences();
        existing.setUserId("user-7");
        when(repository.findByUserId("user-7")).thenReturn(Optional.of(existing));

        service.deletePreferences("user-7");

        verify(repository).delete(existing);
    }

    @Test
    void deletePreferencesDoesNothingWhenMissing() {
        when(repository.findByUserId("user-8")).thenReturn(Optional.empty());

        service.deletePreferences("user-8");

        verify(repository, never()).delete(any());
    }
}
