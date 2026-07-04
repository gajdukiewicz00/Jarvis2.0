package org.jarvis.common.testsupport;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal {@link HttpServletRequest} test double.
 *
 * <p>jarvis-common has no MockMvc / spring-test / Mockito on its test
 * classpath (it is the shared library other services build their MockMvc
 * tests on top of), so the filters/interceptors defined here are exercised
 * against a hand-rolled fake instead of a mocking framework. Only the
 * handful of methods actually invoked by production code are meaningfully
 * implemented; everything else throws so accidental reliance on
 * unimplemented behavior fails loudly instead of silently returning
 * {@code null}/{@code 0}.</p>
 */
public class FakeHttpServletRequest implements HttpServletRequest {

    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private String requestURI = "/test";
    private String remoteAddr = "127.0.0.1";
    private String method = "GET";

    public FakeHttpServletRequest withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public FakeHttpServletRequest withRequestURI(String uri) {
        this.requestURI = uri;
        return this;
    }

    public FakeHttpServletRequest withRemoteAddr(String addr) {
        this.remoteAddr = addr;
        return this;
    }

    public FakeHttpServletRequest withMethod(String httpMethod) {
        this.method = httpMethod;
        return this;
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String value = headers.get(name);
        return value == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(value));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(headers.keySet());
    }

    @Override
    public String getRequestURI() {
        return requestURI;
    }

    @Override
    public String getRemoteAddr() {
        return remoteAddr;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object o) {
        attributes.put(name, o);
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    // ---- everything below is unused by the filters/interceptors under test ----

    @Override
    public String getAuthType() {
        throw unsupported();
    }

    @Override
    public Cookie[] getCookies() {
        throw unsupported();
    }

    @Override
    public long getDateHeader(String name) {
        throw unsupported();
    }

    @Override
    public int getIntHeader(String name) {
        throw unsupported();
    }

    @Override
    public String getPathInfo() {
        throw unsupported();
    }

    @Override
    public String getPathTranslated() {
        throw unsupported();
    }

    @Override
    public String getContextPath() {
        throw unsupported();
    }

    @Override
    public String getQueryString() {
        throw unsupported();
    }

    @Override
    public String getRemoteUser() {
        throw unsupported();
    }

    @Override
    public boolean isUserInRole(String role) {
        throw unsupported();
    }

    @Override
    public Principal getUserPrincipal() {
        throw unsupported();
    }

    @Override
    public String getRequestedSessionId() {
        throw unsupported();
    }

    @Override
    public StringBuffer getRequestURL() {
        throw unsupported();
    }

    @Override
    public String getServletPath() {
        throw unsupported();
    }

    @Override
    public HttpSession getSession(boolean create) {
        throw unsupported();
    }

    @Override
    public HttpSession getSession() {
        throw unsupported();
    }

    @Override
    public String changeSessionId() {
        throw unsupported();
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        throw unsupported();
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        throw unsupported();
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        throw unsupported();
    }

    @Override
    public boolean authenticate(HttpServletResponse response) {
        throw unsupported();
    }

    @Override
    public void login(String username, String password) {
        throw unsupported();
    }

    @Override
    public void logout() {
        throw unsupported();
    }

    @Override
    public Collection<Part> getParts() {
        throw unsupported();
    }

    @Override
    public Part getPart(String name) {
        throw unsupported();
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw unsupported();
    }

    @Override
    public String getCharacterEncoding() {
        throw unsupported();
    }

    @Override
    public void setCharacterEncoding(String env) {
        throw unsupported();
    }

    @Override
    public int getContentLength() {
        throw unsupported();
    }

    @Override
    public long getContentLengthLong() {
        throw unsupported();
    }

    @Override
    public String getContentType() {
        throw unsupported();
    }

    @Override
    public ServletInputStream getInputStream() {
        throw unsupported();
    }

    @Override
    public String getParameter(String name) {
        throw unsupported();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        throw unsupported();
    }

    @Override
    public String[] getParameterValues(String name) {
        throw unsupported();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        throw unsupported();
    }

    @Override
    public String getProtocol() {
        throw unsupported();
    }

    @Override
    public String getScheme() {
        throw unsupported();
    }

    @Override
    public String getServerName() {
        throw unsupported();
    }

    @Override
    public int getServerPort() {
        throw unsupported();
    }

    @Override
    public BufferedReader getReader() {
        throw unsupported();
    }

    @Override
    public String getRemoteHost() {
        throw unsupported();
    }

    @Override
    public Locale getLocale() {
        throw unsupported();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        throw unsupported();
    }

    @Override
    public boolean isSecure() {
        throw unsupported();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        throw unsupported();
    }

    @Override
    public int getRemotePort() {
        throw unsupported();
    }

    @Override
    public String getLocalName() {
        throw unsupported();
    }

    @Override
    public String getLocalAddr() {
        throw unsupported();
    }

    @Override
    public int getLocalPort() {
        throw unsupported();
    }

    @Override
    public ServletContext getServletContext() {
        throw unsupported();
    }

    @Override
    public AsyncContext startAsync() {
        throw unsupported();
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        throw unsupported();
    }

    @Override
    public boolean isAsyncStarted() {
        throw unsupported();
    }

    @Override
    public boolean isAsyncSupported() {
        throw unsupported();
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw unsupported();
    }

    @Override
    public DispatcherType getDispatcherType() {
        throw unsupported();
    }

    @Override
    public String getRequestId() {
        throw unsupported();
    }

    @Override
    public String getProtocolRequestId() {
        throw unsupported();
    }

    @Override
    public ServletConnection getServletConnection() {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not implemented in FakeHttpServletRequest");
    }
}
