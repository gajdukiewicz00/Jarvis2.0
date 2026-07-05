package org.jarvis.apigateway.pccontrol;

import java.util.Map;

/**
 * Request body for the official desktop-action route
 * ({@code POST /api/v1/pc/desktop/action}, Roadmap #1.8/#8).
 *
 * <p>{@code confirm} is gateway-only signal — it is consumed by
 * {@link DesktopActionPolicy} to gate GUARDED actions and is never forwarded
 * to pc-control (which forwards {@code type}/{@code parameters} as a
 * {@code PcActionRequest}-shaped payload instead).</p>
 */
public record DesktopActionRequest(String type, Map<String, String> parameters, boolean confirm) {

    public DesktopActionRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
