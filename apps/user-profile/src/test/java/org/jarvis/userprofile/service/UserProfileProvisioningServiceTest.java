package org.jarvis.userprofile.service;

import org.jarvis.userprofile.domain.UserProfile;
import org.jarvis.userprofile.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileProvisioningServiceTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    @Test
    void ensureProfileExistsCreatesDefaultProfileWhenMissing() {
        when(userProfileRepository.existsByUserId("user-1")).thenReturn(false);

        UserProfileProvisioningService service = new UserProfileProvisioningService(userProfileRepository);
        service.ensureProfileExists("user-1");

        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(profileCaptor.capture());
        UserProfile profile = profileCaptor.getValue();
        assertEquals("user-1", profile.getUserId());
        assertEquals("user-1", profile.getDisplayName());
        assertEquals("UTC", profile.getTimezone());
        assertEquals("en", profile.getLanguage());
    }

    @Test
    void ensureProfileExistsDoesNothingWhenProfileAlreadyExists() {
        when(userProfileRepository.existsByUserId("user-1")).thenReturn(true);

        UserProfileProvisioningService service = new UserProfileProvisioningService(userProfileRepository);
        service.ensureProfileExists("user-1");

        verify(userProfileRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ensureProfileExistsIsIdempotentWhenConcurrentInsertWinsRace() {
        // Simulates two concurrent requests for a brand-new user: both see
        // existsByUserId() == false, both attempt the INSERT, and the loser
        // hits the unique-constraint violation. The loser must swallow it
        // instead of letting the DataIntegrityViolationException propagate,
        // since the profile now exists (created by the winner).
        when(userProfileRepository.existsByUserId("user-1"))
                .thenReturn(false)
                .thenReturn(true);
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        UserProfileProvisioningService service = new UserProfileProvisioningService(userProfileRepository);

        assertDoesNotThrow(() -> service.ensureProfileExists("user-1"));

        verify(userProfileRepository, times(2)).existsByUserId("user-1");
    }

    @Test
    void ensureProfileExistsRethrowsWhenConstraintViolationIsNotARace() {
        // If the profile still doesn't exist after the violation, this was
        // some other integrity issue, not a benign concurrent-insert race,
        // and must still surface to the caller.
        when(userProfileRepository.existsByUserId("user-1")).thenReturn(false);
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenThrow(new DataIntegrityViolationException("some other violation"));

        UserProfileProvisioningService service = new UserProfileProvisioningService(userProfileRepository);

        assertThrows(DataIntegrityViolationException.class, () -> service.ensureProfileExists("user-1"));
    }
}
