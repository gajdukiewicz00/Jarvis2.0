package org.jarvis.apigateway.pccontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DesktopActionPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"OPEN_APP", "OPEN_URL", "WINDOW_FOCUS", "SCREENSHOT",
            "open_app", "open-app", "  open_app  "})
    void classifiesSafeActions(String rawType) {
        assertEquals(DesktopActionClass.SAFE, DesktopActionPolicy.classify(rawType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TYPE_TEXT", "HOTKEY", "type_text", "hotkey"})
    void classifiesGuardedActions(String rawType) {
        assertEquals(DesktopActionClass.GUARDED, DesktopActionPolicy.classify(rawType));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE_FILE", "SEND_MESSAGE", "SEND_EMAIL", "INSTALL_PACKAGE",
            "RUN_ARBITRARY_COMMAND", "RUN_SHELL", "SYSTEM_COMMAND",
            "COMMIT_PUSH_CODE", "MODIFY_SECURITY_SETTINGS", "EXPOSE_SECRET", "SHUTDOWN"
    })
    void classifiesDangerousActions(String rawType) {
        assertEquals(DesktopActionClass.DANGEROUS, DesktopActionPolicy.classify(rawType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEDIA_CONTROL", "VOLUME_UP", "NOTIFY", "SCENARIO", "something-unrecognized"})
    void classifiesUnknownActionsOutsideTheDesktopAllowlist(String rawType) {
        assertEquals(DesktopActionClass.UNKNOWN, DesktopActionPolicy.classify(rawType));
    }

    @Test
    void classifiesNullAndBlankAsUnknown() {
        assertEquals(DesktopActionClass.UNKNOWN, DesktopActionPolicy.classify(null));
        assertEquals(DesktopActionClass.UNKNOWN, DesktopActionPolicy.classify("   "));
    }

    @Test
    void normalizeTrimsHyphensAndUppercases() {
        assertEquals("OPEN_APP", DesktopActionPolicy.normalize("open-app"));
        assertEquals("HOTKEY", DesktopActionPolicy.normalize("  hotkey  "));
        assertNull(DesktopActionPolicy.normalize(null));
        assertNull(DesktopActionPolicy.normalize("  "));
    }
}
