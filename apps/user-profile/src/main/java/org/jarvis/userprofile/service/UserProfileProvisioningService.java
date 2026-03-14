package org.jarvis.userprofile.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.userprofile.domain.UserProfile;
import org.jarvis.userprofile.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileProvisioningService {

    private final UserProfileRepository userProfileRepository;

    @Transactional
    public void ensureProfileExists(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }

        if (userProfileRepository.existsByUserId(userId)) {
            return;
        }

        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setDisplayName(userId);
        profile.setTimezone("UTC");
        profile.setLanguage("en");
        userProfileRepository.save(profile);
    }
}
