package org.jarvis.pccontrol.model;

public record VolumeState(int level, boolean muted, String backend) {

    public VolumeState {
        if (level < 0 || level > 100) {
            throw new IllegalArgumentException("Volume level must be between 0 and 100");
        }
    }
}
