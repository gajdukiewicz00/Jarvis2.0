package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.GpuStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GpuStatusService {

    private final ShellCommandRunner commandRunner;
    private final VisionSecurityProperties properties;

    public GpuStatus currentStatus() {
        boolean preferGpu = properties.getGpu().isPreferIfAvailable();
        if (!commandRunner.isAvailable("nvidia-smi")) {
            return new GpuStatus(preferGpu, false, "cpu", "NVIDIA runtime not detected; CPU baseline is active.");
        }

        try {
            ShellCommandRunner.CommandResult result = commandRunner.execute(List.of(
                    "nvidia-smi",
                    "--query-gpu=name,driver_version",
                    "--format=csv,noheader"));
            if (result.exitCode() != 0) {
                return new GpuStatus(preferGpu, false, "cpu",
                        "nvidia-smi is present but failed: " + result.output());
            }
            String summary = result.output().lines().findFirst().orElse("NVIDIA GPU detected").trim();
            return new GpuStatus(
                    preferGpu,
                    true,
                    "cpu",
                    summary + ". CPU OpenCV baseline remains active in the MVP service."
            );
        } catch (Exception ex) {
            return new GpuStatus(preferGpu, false, "cpu", ex.getMessage());
        }
    }
}
