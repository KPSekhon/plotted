package app.plotted.availability.persistence

import app.plotted.availability.domain.AvailabilityChange
import app.plotted.availability.domain.ChangeType
import app.plotted.generated.jooq.tables.references.AVAILABILITY_CHANGES
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The availability change log.
 *
 * `availability_changes` has existed since V5 and nothing wrote to it. The diff
 * that produces these rows was already being computed on every refresh, used to
 * open and close availability windows, and then discarded — so the history Plot
 * Armour is meant to learn from was being thrown away once a night.
 *
 * Distinct from `availability_snapshots`, which stores the whole picture per
 * title per night. A snapshot answers "what did this look like on the 3rd"; a
 * change answers "when did this leave Crave", and reconstructing the second from
 * the first means diffing every consecutive pair of snapshots forever.
 */
@Repository
class AvailabilityChangeRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    /**
     * Records one change.
     *
     * Takes the fields rather than an [AvailabilityChange], because
     * `detected_at` is this repository's to set from the clock. A write path
     * accepting it would let a caller pass a placeholder, and a change log whose
     * timestamps some caller invented is not a change log.
     */
    fun record(
        titleId: UUID,
        providerId: UUID,
        regionCode: String,
        changeType: ChangeType,
        oldAccessType: String?,
        newAccessType: String?,
        confidence: Double,
    ): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(AVAILABILITY_CHANGES)
            .set(AVAILABILITY_CHANGES.ID, id)
            .set(AVAILABILITY_CHANGES.TITLE_ID, titleId)
            .set(AVAILABILITY_CHANGES.PROVIDER_ID, providerId)
            .set(AVAILABILITY_CHANGES.REGION_CODE, regionCode)
            .set(AVAILABILITY_CHANGES.CHANGE_TYPE, changeType.dbValue)
            .set(AVAILABILITY_CHANGES.OLD_ACCESS_TYPE, oldAccessType)
            .set(AVAILABILITY_CHANGES.NEW_ACCESS_TYPE, newAccessType)
            .set(AVAILABILITY_CHANGES.DETECTED_AT, OffsetDateTime.now(clock))
            .set(AVAILABILITY_CHANGES.CONFIDENCE, BigDecimal.valueOf(confidence))
            .execute()
        return id
    }

    /**
     * Recent changes for a title, newest first.
     *
     * Kept for the title page and for anyone auditing why an alert fired. The
     * limit is there because a title bouncing between providers can accumulate a
     * lot of rows and no screen wants all of them.
     */
    fun recentFor(titleId: UUID, limit: Int): List<AvailabilityChange> = dsl.select(
        AVAILABILITY_CHANGES.TITLE_ID,
        AVAILABILITY_CHANGES.PROVIDER_ID,
        AVAILABILITY_CHANGES.REGION_CODE,
        AVAILABILITY_CHANGES.CHANGE_TYPE,
        AVAILABILITY_CHANGES.OLD_ACCESS_TYPE,
        AVAILABILITY_CHANGES.NEW_ACCESS_TYPE,
        AVAILABILITY_CHANGES.DETECTED_AT,
        AVAILABILITY_CHANGES.CONFIDENCE,
    )
        .from(AVAILABILITY_CHANGES)
        .where(AVAILABILITY_CHANGES.TITLE_ID.eq(titleId))
        .orderBy(AVAILABILITY_CHANGES.DETECTED_AT.desc())
        .limit(limit)
        .fetch()
        .map {
            AvailabilityChange(
                titleId = it[AVAILABILITY_CHANGES.TITLE_ID]!!,
                providerId = it[AVAILABILITY_CHANGES.PROVIDER_ID]!!,
                regionCode = it[AVAILABILITY_CHANGES.REGION_CODE]!!,
                changeType = ChangeType.fromDb(it[AVAILABILITY_CHANGES.CHANGE_TYPE]!!),
                oldAccessType = it[AVAILABILITY_CHANGES.OLD_ACCESS_TYPE],
                newAccessType = it[AVAILABILITY_CHANGES.NEW_ACCESS_TYPE],
                detectedAt = it[AVAILABILITY_CHANGES.DETECTED_AT]!!.toInstant(),
                confidence = it[AVAILABILITY_CHANGES.CONFIDENCE]!!.toDouble(),
            )
        }
}
