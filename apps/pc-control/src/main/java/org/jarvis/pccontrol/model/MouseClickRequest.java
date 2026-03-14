package org.jarvis.pccontrol.model;

public record MouseClickRequest(int x, int y, Integer button) {

    public MouseClickRequest {
        button = button == null ? 1 : button;
    }
}
