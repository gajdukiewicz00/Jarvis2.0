package org.jarvis.android.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [HealthSnapshot] is a plain data class with no Android dependency — unlike
 * [HealthConnectManager] in the same file (which needs a real `Context` and the Health
 * Connect runtime and is therefore skipped for JVM unit testing).
 */
class HealthSnapshotTest {

    @Test
    fun exposesSleepHoursAndStepsAsProvided() {
        val snapshot = HealthSnapshot(sleepHours = 7.5, steps = 8321L)

        assertEquals(7.5, snapshot.sleepHours, 0.0001)
        assertEquals(8321L, snapshot.steps)
    }

    @Test
    fun copyOverridesOnlyRequestedField() {
        val snapshot = HealthSnapshot(sleepHours = 6.0, steps = 100L)

        val updated = snapshot.copy(steps = 200L)

        assertEquals(6.0, updated.sleepHours, 0.0001)
        assertEquals(200L, updated.steps)
    }

    @Test
    fun equalsIsValueBasedNotReferenceBased() {
        val a = HealthSnapshot(sleepHours = 8.0, steps = 500L)
        val b = HealthSnapshot(sleepHours = 8.0, steps = 500L)
        val c = HealthSnapshot(sleepHours = 8.0, steps = 501L)

        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
