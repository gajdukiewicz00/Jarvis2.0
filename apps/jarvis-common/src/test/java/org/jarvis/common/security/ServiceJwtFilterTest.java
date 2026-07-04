package org.jarvis.common.security;

import org.jarvis.common.testsupport.FakeHttpServletRequest;
import org.jarvis.common.testsupport.FakeHttpServletResponse;
import org.jarvis.common.testsupport.RecordingFilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceJwtFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ServiceJwtProvider provider(boolean required) {
        return new ServiceJwtProvider(SECRET, "", "jarvis-internal", "jarvis-services", 300, required, true);
    }

    @Test
    void noTokenHeaderLeavesContextEmptyButContinuesChain() throws Exception {
        ServiceJwtFilter filter = new ServiceJwtFilter(provider(true));
        FakeHttpServletRequest request = new FakeHttpServletRequest().withRequestURI("/api/x");
        FakeHttpServletResponse response = new FakeHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertTrue(chain.wasCalled());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void validTokenPopulatesSecurityContextWithSubjectAndRoles() throws Exception {
        ServiceJwtProvider provider = provider(true);
        String token = provider.createToken("caller-service", "caller-service", List.of("ROLE_ONE", "ROLE_TWO"));
        ServiceJwtFilter filter = new ServiceJwtFilter(provider);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(ServiceJwtFilter.SERVICE_TOKEN_HEADER, token);
        FakeHttpServletResponse response = new FakeHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertTrue(chain.wasCalled());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("caller-service", auth.getName());
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertEquals(Set.of("ROLE_ONE", "ROLE_TWO"), authorities);
        assertEquals("service-jwt", auth.getDetails());
    }

    @Test
    void tokenWithNoRolesGetsDefaultSvcInternalAuthority() throws Exception {
        ServiceJwtProvider provider = provider(true);
        String token = provider.createToken("caller-service", List.of());
        ServiceJwtFilter filter = new ServiceJwtFilter(provider);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(ServiceJwtFilter.SERVICE_TOKEN_HEADER, token);
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        filter.doFilterInternal(request, response, new RecordingFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(Set.of("SVC_INTERNAL"),
                auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
    }

    @Test
    void invalidTokenLeavesContextEmptyButContinuesChain() throws Exception {
        ServiceJwtFilter filter = new ServiceJwtFilter(provider(true));
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(ServiceJwtFilter.SERVICE_TOKEN_HEADER, "not-a-real-jwt")
                .withRequestURI("/api/y");
        FakeHttpServletResponse response = new FakeHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertTrue(chain.wasCalled());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void existingAuthenticationIsNotOverwritten() throws Exception {
        ServiceJwtProvider provider = provider(true);
        String token = provider.createToken("caller-service", List.of("ROLE_ONE"));
        UsernamePasswordAuthenticationToken existing =
                new UsernamePasswordAuthenticationToken("already-authenticated", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(existing);
        ServiceJwtFilter filter = new ServiceJwtFilter(provider);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(ServiceJwtFilter.SERVICE_TOKEN_HEADER, token);
        FakeHttpServletResponse response = new FakeHttpServletResponse();

        filter.doFilterInternal(request, response, new RecordingFilterChain());

        assertEquals("already-authenticated", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void disabledProviderNeverTouchesSecurityContext() throws Exception {
        ServiceJwtProvider disabledProvider = new ServiceJwtProvider("", "", "jarvis-internal", "jarvis-services",
                300, false, true);
        ServiceJwtFilter filter = new ServiceJwtFilter(disabledProvider);
        FakeHttpServletRequest request = new FakeHttpServletRequest()
                .withHeader(ServiceJwtFilter.SERVICE_TOKEN_HEADER, "irrelevant");
        FakeHttpServletResponse response = new FakeHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertTrue(chain.wasCalled());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
