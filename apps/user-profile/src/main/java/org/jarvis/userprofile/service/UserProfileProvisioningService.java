package org.jarvis.userprofile.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.userprofile.domain.UserProfile;
import org.jarvis.userprofile.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileProvisioningService {

    private final UserProfileRepository userProfileRepository;

    // REQUIRES_NEW so lazy provisioning runs in its own writable transaction.
    // The read endpoints (getGoals/getPlanningContext/...) call this from a
    // @Transactional(readOnly = true) context; with the default REQUIRED
    // propagation the INSERT joined that read-only tx and failed with
    // "cannot execute INSERT in a read-only transaction" for any user whose
    // profile row didn't exist yet.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
