package org.jarvis.apigateway.pccontrol;

/**
 * Classification result from {@link DesktopActionPolicy#classify(String)}.
 * Mirrors the SAFE/GUARDED/DANGEROUS/UNKNOWN taxonomy used by the host
 * bridge's {@code classify_action} (scripts/jarvis-host-bridge.sh) so both
 * enforcement points agree on what is allowed.
 */
public enum DesktopActionClass {
    /** Allowed to execute immediately: open app/url, focus window, screenshot. */
    SAFE,
    /** Allowed only when the caller sets {@code confirm=true}: type text, hotkey. */
    GUARDED,
    /** Never allowed through this route: shell exec, delete file, install packages, send messages, etc. */
    DANGEROUS,
    /** Not recognized by the desktop-action allowlist; treated as a refusal (default-deny). */
    UNKNOWN
}
