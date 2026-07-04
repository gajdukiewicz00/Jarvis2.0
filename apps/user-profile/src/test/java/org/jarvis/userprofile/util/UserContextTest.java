package org.jarvis.userprofile.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserContextTest {

    @Mock
    private HttpServletRequest request;

    @Test
    void getUserIdReadsUserIdHeader() {
        when(request.getHeader("X-User-Id")).thenReturn("user-42");

        assertEquals("user-42", UserContext.getUserId(request));
    }

    @Test
    void getUsernameReadsUsernameHeader() {
        when(request.getHeader("X-Username")).thenReturn("denis");

        assertEquals("denis", UserContext.getUsername(request));
    }

    @Test
    void getUserRoleReadsUserRoleHeader() {
        when(request.getHeader("X-User-Role")).thenReturn("ADMIN");

        assertEquals("ADMIN", UserContext.getUserRole(request));
    }

    @Test
    void isAdminReturnsTrueForAdminRoleCaseInsensitive() {
        when(request.getHeader("X-User-Role")).thenReturn("admin");

        assertTrue(UserContext.isAdmin(request));
    }

    @Test
    void isAdminReturnsFalseForNonAdminRole() {
        when(request.getHeader("X-User-Role")).thenReturn("USER");

        assertFalse(UserContext.isAdmin(request));
    }

    @Test
    void isAuthenticatedReturnsTrueWhenUserIdPresent() {
        when(request.getHeader("X-User-Id")).thenReturn("user-42");

        assertTrue(UserContext.isAuthenticated(request));
    }

    @Test
    void isAuthenticatedReturnsFalseWhenUserIdMissing() {
        when(request.getHeader("X-User-Id")).thenReturn(null);

        assertFalse(UserContext.isAuthenticated(request));
    }

    @Test
    void isAuthenticatedReturnsFalseWhenUserIdBlank() {
        when(request.getHeader("X-User-Id")).thenReturn("");

        assertFalse(UserContext.isAuthenticated(request));
    }
}
