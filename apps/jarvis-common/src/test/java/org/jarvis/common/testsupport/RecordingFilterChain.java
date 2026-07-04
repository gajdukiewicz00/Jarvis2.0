package org.jarvis.common.testsupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Records whether {@link #doFilter} was invoked, so filter tests can assert
 * the chain was (or was not) continued without needing a mocking framework.
 */
public class RecordingFilterChain implements FilterChain {

    private boolean called;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) {
        called = true;
    }

    public boolean wasCalled() {
        return called;
    }
}
