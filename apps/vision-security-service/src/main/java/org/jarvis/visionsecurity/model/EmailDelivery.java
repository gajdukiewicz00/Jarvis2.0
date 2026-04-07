package org.jarvis.visionsecurity.model;

public record EmailDelivery(
        boolean attempted,
        boolean sent,
        String message
) {
}
