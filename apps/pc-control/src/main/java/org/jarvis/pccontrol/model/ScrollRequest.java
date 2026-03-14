package org.jarvis.pccontrol.model;

public record ScrollRequest(int amount, String direction) {

    public ScrollRequest {
        direction = direction == null ? "down" : direction.toLowerCase();
    }
}
