package org.jarvis.pccontrol.exception;

public class UnsupportedDisplayServerException extends DesktopControlException {

    private final String detectedServer;
    private final String requiredServer;

    public UnsupportedDisplayServerException(String detectedServer, String requiredServer) {
        super("Operation requires " + requiredServer + " but detected " + detectedServer);
        this.detectedServer = detectedServer;
        this.requiredServer = requiredServer;
    }

    public String getDetectedServer() {
        return detectedServer;
    }

    public String getRequiredServer() {
        return requiredServer;
    }
}
