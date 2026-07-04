package org.jarvis.userprofile.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserProfileTest {

    @Test
    void gettersAndSettersRoundTripAllFields() {
        UserProfile profile = new UserProfile();
        profile.setId(1L);
        profile.setUserId("user-1");
        profile.setDisplayName("User One");
        profile.setTimezone("UTC");
        profile.setLanguage("en");
        LocalDateTime now = LocalDateTime.now();
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);

        assertEquals(1L, profile.getId());
        assertEquals("user-1", profile.getUserId());
        assertEquals("User One", profile.getDisplayName());
        assertEquals("UTC", profile.getTimezone());
        assertEquals("en", profile.getLanguage());
        assertEquals(now, profile.getCreatedAt());
        assertEquals(now, profile.getUpdatedAt());
    }

    @Test
    void allArgsConstructorProducesEqualAndConsistentInstances() {
        LocalDateTime now = LocalDateTime.now();
        UserProfile a = new UserProfile(1L, "user-1", "User One", "UTC", "en", now, now);
        UserProfile b = new UserProfile(1L, "user-1", "User One", "UTC", "en", now, now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new UserProfile());
        assertNotNull(a.toString());
    }

    @Test
    void onCreateSetsCreatedAndUpdatedTimestamps() {
        UserProfile profile = new UserProfile();

        profile.onCreate();

        assertNotNull(profile.getCreatedAt());
        assertNotNull(profile.getUpdatedAt());
    }

    @Test
    void onUpdateRefreshesUpdatedAtTimestamp() {
        UserProfile profile = new UserProfile();
        profile.onCreate();

        profile.onUpdate();

        assertNotNull(profile.getUpdatedAt());
    }
}
