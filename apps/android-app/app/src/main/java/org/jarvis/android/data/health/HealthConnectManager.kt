package org.jarvis.android.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Local-first health snapshot read from Health Connect (on-device, no cloud). */
data class HealthSnapshot(val sleepHours: Double, val steps: Long)

/**
 * Reads sleep + steps from Health Connect. The data stays on the phone until
 * the user chooses to sync it (E2E-encrypted) to the home server.
 */
class HealthConnectManager(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun hasAllPermissions(): Boolean =
        client().permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun read(): HealthSnapshot {
        val now = Instant.now()
        val dayAgo = now.minus(24, ChronoUnit.HOURS)
        val startOfToday = startOfLocalDay(now)
        val hc = client()

        val sleepMinutes = hc.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(dayAgo, now))
        ).records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }

        val steps = hc.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(startOfToday, now))
        ).records.sumOf { it.count }

        return HealthSnapshot(sleepHours = sleepMinutes / 60.0, steps = steps)
    }
}

/**
 * Computes the start of the local calendar day containing [now], expressed as an [Instant].
 *
 * Extracted as a plain top-level function (rather than inlined via
 * `now.truncatedTo(ChronoUnit.DAYS)`) so it can be unit tested without a Health Connect runtime
 * / Android [Context]: [Instant.truncatedTo] truncates at the UTC epoch-day boundary because an
 * [Instant] carries no timezone offset, which computed the wrong 24h window for "today" for any
 * non-UTC user (finding #53). Converting through [ZoneId] first anchors the day boundary to the
 * device's actual local calendar day.
 */
fun startOfLocalDay(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
    now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
