package org.jarvis.pccontrol.model;

import java.util.List;

public record DesktopApplicationsResponse(List<DesktopApplication> applications, int count) {

    public DesktopApplicationsResponse {
        applications = applications == null ? List.of() : List.copyOf(applications);
        count = count <= 0 ? applications.size() : count;
    }
}
