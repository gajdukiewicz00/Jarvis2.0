package org.jarvis.pccontrol.model;

import java.util.List;

public record WindowListResponse(List<WindowInfo> windows, int count) {

    public WindowListResponse {
        windows = windows == null ? List.of() : List.copyOf(windows);
    }
}
