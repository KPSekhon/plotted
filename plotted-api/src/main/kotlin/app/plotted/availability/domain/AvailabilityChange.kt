package app.plotted.availability.domain

import java.time.Instant
import java.util.UUID

/**
 * One observed change in where a title can be watched.
 *
 * Recorded from the nightly diff. `confidence` travels with it because the diff
 * is only as good as the feed that produced it: a pass where some providers
 * could not be mapped sees fewer offers than exist, and a "removal" derived from
 * a partial picture is a guess. Plot Armour reads this before deciding whether a
 * change is worth telling anyone about.
 */
data class AvailabilityChange(
    val titleId: UUID,
    val providerId: UUID,
    val regionCode: String,
    val changeType: ChangeType,
    val oldAccessType: String?,
    val newAccessType: String?,
    val detectedAt: Instant,
    val confidence: Double,
)

enum class ChangeType(val dbValue: String) {
    ADDED("added"),
    REMOVED("removed"),
    ACCESS_TYPE_CHANGED("access_type_changed"),
    PRICE_CHANGED("price_changed"),
    ;

    companion object {
        fun fromDb(value: String): ChangeType =
            entries.firstOrNull { it.dbValue == value } ?: error("Unknown availability change type '$value'")
    }
}
