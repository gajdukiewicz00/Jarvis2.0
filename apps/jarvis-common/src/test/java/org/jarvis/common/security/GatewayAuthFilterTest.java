package org.jarvis.common.security;

import org.jarvis.common.testsupport.FakeHttpServletRequest;
import org.jarvis.common.testsupport.FakeHttpServletResponse;
import org.jarvis.common.testsupport.RecordingFilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayAuthFilterTest {

    private final GatewayAuthFilter filter = new GatewayAuthFilter();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noExistingAuthenticationJustContinuesChain() throws Exception {
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(GatewayAuthFilter.USER_ID_HEADER, "user-1");
        FakeHttpServletResponse response = new FakeHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertTrue(chain.wasCalled());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void unauthenticatedExistingAuthenticationIsLeftUntouched() throws Exception {
        UsernamePasswordAuthenticationToken notYetAuthenticated =
                new UsernamePasswordAuthenticationToken("svc", "creds");
        SecurityContextHolder.getContext().setAuthentication(notYetAuthenticated);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(GatewayAuthFilter.USER_ID_HEADER, "user-1");
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        filter.doFilterInternal(request, response, new RecordingFilterChain());

        assertSame(notYetAuthenticated, SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void nonServiceAuthenticationIsNotDelegated() throws Exception {
        UsernamePasswordAuthenticationToken userAuth = new UsernamePasswordAuthenticationToken(
                "regular-user", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(userAuth);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(GatewayAuthFilter.USER_ID_HEADER, "delegated-user");
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        filter.doFilterInternal(request, response, new RecordingFilterChain());

        assertSame(userAuth, SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingUserIdHeaderIsNotDelegatedEvenForServiceAuthentication() throws Exception {
        UsernamePasswordAuthenticationToken serviceAuth = new UsernamePasswordAuthenticationToken(
                "api-gateway", null, List.of(new SimpleGrantedAuthority("SVC_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(serviceAuth);
        FakeHttpServletRequest request = new FakeHttpServletRequest();
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        filter.doFilterInternal(request, response, new RecordingFilterChain());

        assertSame(serviceAuth, SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void delegatesUserIdentityWhenServiceAuthenticatedAndUserIdPresent() throws Exception {
        UsernamePasswordAuthenticationToken serviceAuth = new UsernamePasswordAuthenticationToken(
                "api-gateway", null, List.of(new SimpleGrantedAuthority("SVC_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(serviceAuth);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(GatewayAuthFilter.USER_ID_HEADER, "user-42")
                .withHeader(GatewayAuthFilter.USER_ROLES_HEADER, "ROLE_ADMIN, ROLE_OWNER");
        FakeHttpServletResponse response = new FakeHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertTrue(chain.wasCalled());
        Authentication merged = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("user-42", merged.getName());
        assertEquals("delegated-by:api-gateway", merged.getDetails());
        Set<String> authorities = merged.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertEquals(Set.of("SVC_INTERNAL", "ROLE_ADMIN", "ROLE_OWNER"), authorities);
    }

    @Test
    void blankRolesHeaderResultsInOnlyOriginalAuthorities() throws Exception {
        UsernamePasswordAuthenticationToken serviceAuth = new UsernamePasswordAuthenticationToken(
                "api-gateway", null, List.of(new SimpleGrantedAuthority("SVC_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(serviceAuth);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(GatewayAuthFilter.USER_ID_HEADER, "user-7")
                .withHeader(GatewayAuthFilter.USER_ROLES_HEADER, "   ");
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        filter.doFilterInternal(request, response, new RecordingFilterChain());

        Authentication merged = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(Set.of("SVC_INTERNAL"),
                merged.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
    }

    @Test
    void blankUserIdHeaderIsTreatedAsAbsent() throws Exception {
        UsernamePasswordAuthenticationToken serviceAuth = new UsernamePasswordAuthenticationToken(
                "api-gateway", null, List.of(new SimpleGrantedAuthority("SVC_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(serviceAuth);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(GatewayAuthFilter.USER_ID_HEADER, "   ");
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        filter.doFilterInternal(request, response, new RecordingFilterChain());

        assertSame(serviceAuth, SecurityContextHolder.getContext().getAuthentication());
        assertNull(serviceAuth.getDetails());
    }
}
