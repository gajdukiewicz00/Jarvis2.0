package org.jarvis.planner.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserContextTest {

    @Test
    void extractsHeadersAndDetectsAdminCaseInsensitively() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "user-1");
        request.addHeader("X-Username", "jarvis");
        request.addHeader("X-User-Role", "admin");

        assertEquals("user-1", UserContext.getUserId(request));
        assertEquals("jarvis", UserContext.getUsername(request));
        assertEquals("admin", UserContext.getUserRole(request));
        assertTrue(UserContext.isAdmin(request));
        assertTrue(UserContext.isAuthenticated(request));
    }

    @Test
    void treatsMissingHeadersAsAnonymousNonAdminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertFalse(UserContext.isAdmin(request));
        assertFalse(UserContext.isAuthenticated(request));
    }

    @Test
    void treatsEmptyUserIdAsUnauthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "");

        assertFalse(UserContext.isAuthenticated(request));
    }
}
