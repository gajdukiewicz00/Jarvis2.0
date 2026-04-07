package org.jarvis.visionsecurity.model;

public record GpuStatus(
        boolean preferGpu,
        boolean available,
        String activeBackend,
        String detail
) {
}
