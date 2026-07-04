package org.jarvis.userprofile.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserPriorityTest {

    @Test
    void gettersAndSettersRoundTripAllFields() {
        UserPriority priority = new UserPriority();
        priority.setId(1L);
        priority.setUserId("user-1");
        priority.setName("Backend");
        priority.setLevel(2);
        priority.setDescription("Keep services healthy");
        LocalDateTime now = LocalDateTime.now();
        priority.setUpdatedAt(now);

        assertEquals(1L, priority.getId());
        assertEquals("user-1", priority.getUserId());
        assertEquals("Backend", priority.getName());
        assertEquals(2, priority.getLevel());
        assertEquals("Keep services healthy", priority.getDescription());
        assertEquals(now, priority.getUpdatedAt());
    }

    @Test
    void allArgsConstructorProducesEqualAndConsistentInstances() {
        LocalDateTime now = LocalDateTime.now();
        UserPriority a = new UserPriority(1L, "user-1", "Backend", 1, "Notes", now);
        UserPriority b = new UserPriority(1L, "user-1", "Backend", 1, "Notes", now);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new UserPriority());
        assertNotNull(a.toString());
    }

    @Test
    void onCreateDefaultsLevelWhenMissing() {
        UserPriority priority = new UserPriority();

        priority.onCreate();

        assertEquals(3, priority.getLevel());
        assertNotNull(priority.getUpdatedAt());
    }

    @Test
    void onCreateKeepsExplicitlySetLevel() {
        UserPriority priority = new UserPriority();
        priority.setLevel(1);

        priority.onCreate();

        assertEquals(1, priority.getLevel());
    }

    @Test
    void onUpdateRefreshesUpdatedAtTimestamp() {
        UserPriority priority = new UserPriority();
        priority.onCreate();

        priority.onUpdate();

        assertNotNull(priority.getUpdatedAt());
    }
}
