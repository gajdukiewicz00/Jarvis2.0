package org.jarvis.common.testsupport;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal {@link HttpServletResponse} test double. See {@link FakeHttpServletRequest}
 * for why a hand-rolled fake is used instead of a mocking framework.
 */
public class FakeHttpServletResponse implements HttpServletResponse {

    private final Map<String, String> headers = new LinkedHashMap<>();
    private final StringWriter body = new StringWriter();
    private final PrintWriter writer = new PrintWriter(body);
    private int status = SC_OK;
    private String contentType;

    @Override
    public void setStatus(int sc) {
        this.status = sc;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void setContentType(String type) {
        this.contentType = type;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public PrintWriter getWriter() {
        return writer;
    }

    public String bodyAsString() {
        writer.flush();
        return body.toString();
    }

    @Override
    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        String value = headers.get(name);
        return value == null ? List.of() : List.of(value);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return headers.keySet();
    }

    @Override
    public boolean containsHeader(String name) {
        return headers.containsKey(name);
    }

    // ---- everything below is unused by the interceptors/filters under test ----

    @Override
    public void addCookie(Cookie cookie) {
        throw unsupported();
    }

    @Override
    public String encodeURL(String url) {
        throw unsupported();
    }

    @Override
    public String encodeRedirectURL(String url) {
        throw unsupported();
    }

    @Override
    public void sendError(int sc, String msg) {
        throw unsupported();
    }

    @Override
    public void sendError(int sc) {
        throw unsupported();
    }

    @Override
    public void sendRedirect(String location) {
        throw unsupported();
    }

    @Override
    public void setDateHeader(String name, long date) {
        throw unsupported();
    }

    @Override
    public void addDateHeader(String name, long date) {
        throw unsupported();
    }

    @Override
    public void setIntHeader(String name, int value) {
        throw unsupported();
    }

    @Override
    public void addIntHeader(String name, int value) {
        throw unsupported();
    }

    @Override
    public String getCharacterEncoding() {
        throw unsupported();
    }

    @Override
    public ServletOutputStream getOutputStream() {
        throw unsupported();
    }

    @Override
    public void setCharacterEncoding(String charset) {
        throw unsupported();
    }

    @Override
    public void setContentLength(int len) {
        throw unsupported();
    }

    @Override
    public void setContentLengthLong(long len) {
        throw unsupported();
    }

    @Override
    public void setBufferSize(int size) {
        throw unsupported();
    }

    @Override
    public int getBufferSize() {
        throw unsupported();
    }

    @Override
    public void flushBuffer() {
        throw unsupported();
    }

    @Override
    public void resetBuffer() {
        throw unsupported();
    }

    @Override
    public boolean isCommitted() {
        throw unsupported();
    }

    @Override
    public void reset() {
        throw unsupported();
    }

    @Override
    public void setLocale(Locale loc) {
        throw unsupported();
    }

    @Override
    public Locale getLocale() {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not implemented in FakeHttpServletResponse");
    }
}
