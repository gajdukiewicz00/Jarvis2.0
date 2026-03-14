package org.jarvis.pccontrol.exception;

public class MissingToolException extends DesktopControlException {

    private final String toolName;

    public MissingToolException(String toolName) {
        super("Required tool not found: " + toolName);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
