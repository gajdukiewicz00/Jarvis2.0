package org.jarvis.pccontrol.exception;

public class DesktopControlException extends RuntimeException {
    public DesktopControlException(String message) {
        super(message);
    }
    
    public DesktopControlException(String message, Throwable cause) {
        super(message, cause);
    }
}
