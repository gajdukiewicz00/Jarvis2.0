package org.jarvis.userprofile.service;

import org.jarvis.userprofile.domain.UserProfile;
import org.jarvis.userprofile.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
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
}
