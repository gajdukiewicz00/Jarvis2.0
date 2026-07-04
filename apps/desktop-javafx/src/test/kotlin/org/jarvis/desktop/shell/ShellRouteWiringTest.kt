package org.jarvis.desktop.shell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-level guard against silently routing the LIFE entry back to the
 * legacy {@code LifeView}. We can't bootstrap the JavaFX shell in unit
 * tests (no display, no application thread), so we read the source file
 * and assert the route mapping line directly.
 *
 * <p>If you intentionally re-route LIFE somewhere new, update this test in
 * the same change so reviewers see the migration is being walked back.</p>
 */
class ShellRouteWiringTest {

    @Test
    fun `LIFE route resolves to LifeMapView in ShellRoot`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/org/jarvis/desktop/shell/ShellRoot.kt")
        )

        assertTrue(
            source.contains("ShellRoute.LIFE -> LifeMapView("),
            "Expected ShellRoot to map ShellRoute.LIFE -> LifeMapView, but the route is no longer wired to the unified Life Map."
        )
        assertFalse(
            source.contains("ShellRoute.LIFE -> LifeView("),
            "ShellRoot still maps ShellRoute.LIFE to the legacy LifeView. The legacy view must stay dev-only."
        )
    }
}
