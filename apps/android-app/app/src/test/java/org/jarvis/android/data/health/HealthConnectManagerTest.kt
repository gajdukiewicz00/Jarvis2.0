package org.jarvis.android.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [startOfLocalDay] — extracted from [HealthConnectManager.read] so it can be
 * tested without a Health Connect runtime / Android `Context` (finding #53: `steps today` must
 * be computed over the device's local calendar day, not the UTC day boundary).
 */
class HealthConnectManagerTest {

    @Test
    fun startOfLocalDay_alignsToLocalMidnightNotUtcMidnight_forEarlyMorningUtcPlusOneUser() {
        // A user in Poland-like UTC+1 opens the app at 01:00 local time on 2026-07-06.
        // In UTC terms that instant is 2026-07-06T00:00:00Z.
        val now = Instant.parse("2026-07-06T00:00:00Z")
        val zone = ZoneOffset.ofHours(1)

        val startOfToday = startOfLocalDay(now, zone)

        // Correct local midnight for 2026-07-06 at UTC+1 is 2026-07-05T23:00:00Z.
        val expectedLocalMidnight = Instant.parse("2026-07-05T23:00:00Z")
        assertEquals(expectedLocalMidnight, startOfToday)

        // The old buggy behavior (now.truncatedTo(ChronoUnit.DAYS)) would have produced
        // 2026-07-06T00:00:00Z here — one hour later than the true local midnight, which
        // would exclude any steps taken in the first hour of the local day.
        val buggyUtcTruncatedStart = Instant.parse("2026-07-06T00:00:00Z")
        assertNotEquals(buggyUtcTruncatedStart, startOfToday)
    }

    @Test
    fun startOfLocalDay_matchesUtcTruncationOnlyWhenZoneIsUtc() {
        val now = Instant.parse("2026-07-06T13:45:00Z")

        val startOfToday = startOfLocalDay(now, ZoneOffset.UTC)

        assertEquals(Instant.parse("2026-07-06T00:00:00Z"), startOfToday)
    }

    @Test
    fun startOfLocalDay_rollsBackToPreviousUtcDay_forNegativeOffsetEveningUser() {
        // A user in UTC-5 at 20:00 local time on 2026-07-05 — still the same local calendar
        // day, but already 2026-07-06 in UTC.
        val now = Instant.parse("2026-07-06T01:00:00Z")
        val zone = ZoneOffset.ofHours(-5)

        val startOfToday = startOfLocalDay(now, zone)

        // Local midnight for 2026-07-05 at UTC-5 is 2026-07-05T05:00:00Z.
        assertEquals(Instant.parse("2026-07-05T05:00:00Z"), startOfToday)
    }
}
