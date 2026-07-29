package app.plotted.availability.persistence

import app.plotted.availability.domain.AccessType
import app.plotted.availability.domain.StoredAvailability
import app.plotted.generated.jooq.tables.references.AVAILABILITY_SNAPSHOTS
import app.plotted.generated.jooq.tables.references.TITLE_AVAILABILITY
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Availability persistence.
 *
 * `title_availability.validity` is a Postgres `DATERANGE` guarded by a GiST
 * exclusion constraint, and neither is visible to the jOOQ generator (see
 * ADR 0002 and ADR 0004). So the range is written through raw SQL fragments
 * here, in the one class that is allowed to know about it.
 *
 * Note there is no upsert. The exclusion constraint cannot serve as an
 * `ON CONFLICT` target -- Postgres only accepts unique indexes there -- so rows
 * are opened and closed explicitly instead. That turns out to be the right shape
 * anyway: closing a row rather than overwriting it is what preserves the history
 * Plot Armour is going to learn from.
 */
@Repository
class AvailabilityRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun findActive(titleId: UUID, regionCode: String): List<StoredAvailability> = dsl.select(
        TITLE_AVAILABILITY.ID,
        TITLE_AVAILABILITY.PROVIDER_ID,
        TITLE_AVAILABILITY.ACCESS_TYPE,
        TITLE_AVAILABILITY.PRICE,
        TITLE_AVAILABILITY.CURRENCY,
        TITLE_AVAILABILITY.DEEP_LINK,
        TITLE_AVAILABILITY.SOURCE_CHECKED_AT,
        TITLE_AVAILABILITY.CONFIDENCE,
    )
        .from(TITLE_AVAILABILITY)
        .where(TITLE_AVAILABILITY.TITLE_ID.eq(titleId))
        .and(TITLE_AVAILABILITY.REGION_CODE.eq(regionCode))
        .and(TITLE_AVAILABILITY.ACTIVE.isTrue)
        .fetch()
        .map { record ->
            StoredAvailability(
                id = record[TITLE_AVAILABILITY.ID]!!,
                providerId = record[TITLE_AVAILABILITY.PROVIDER_ID]!!,
                accessType = AccessType.fromDb(record[TITLE_AVAILABILITY.ACCESS_TYPE]!!),
                price = record[TITLE_AVAILABILITY.PRICE],
                currency = record[TITLE_AVAILABILITY.CURRENCY]?.trim(),
                deepLink = record[TITLE_AVAILABILITY.DEEP_LINK],
                sourceCheckedAt = record[TITLE_AVAILABILITY.SOURCE_CHECKED_AT]!!.toInstant(),
                confidence = record[TITLE_AVAILABILITY.CONFIDENCE]!!,
            )
        }

    /** Opens a new availability window, running from today with no known end. */
    fun open(
        titleId: UUID,
        providerId: UUID,
        regionCode: String,
        accessType: AccessType,
        source: String,
        confidence: BigDecimal,
        price: BigDecimal? = null,
        currency: String? = null,
        deepLink: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val today = LocalDate.now(clock)
        dsl.insertInto(TITLE_AVAILABILITY)
            .set(TITLE_AVAILABILITY.ID, id)
            .set(TITLE_AVAILABILITY.TITLE_ID, titleId)
            .set(TITLE_AVAILABILITY.PROVIDER_ID, providerId)
            .set(TITLE_AVAILABILITY.REGION_CODE, regionCode)
            .set(TITLE_AVAILABILITY.ACCESS_TYPE, accessType.dbValue)
            .set(TITLE_AVAILABILITY.PRICE, price)
            .set(TITLE_AVAILABILITY.CURRENCY, currency)
            .set(TITLE_AVAILABILITY.DEEP_LINK, deepLink)
            .set(TITLE_AVAILABILITY.SOURCE, source)
            .set(TITLE_AVAILABILITY.SOURCE_CHECKED_AT, OffsetDateTime.now(clock))
            .set(TITLE_AVAILABILITY.CONFIDENCE, confidence)
            .set(TITLE_AVAILABILITY.ACTIVE, true)
            // Half-open: [today, ). An unbounded upper bound means "current".
            .set(validityField, DSL.field("daterange({0}, NULL)", String::class.java, DSL.`val`(today)))
            .execute()
        return id
    }

    /**
     * Closes a window that upstream no longer reports.
     *
     * The row is kept with a bounded validity rather than deleted. When it was
     * available is the entire signal Plot Armour's removal-risk model learns
     * from, and it cannot be recovered once thrown away.
     */
    fun close(id: UUID) {
        dsl.update(TITLE_AVAILABILITY)
            .set(validityField, DSL.field("daterange(lower(validity), {0})", String::class.java, DSL.`val`(LocalDate.now(clock))))
            .set(TITLE_AVAILABILITY.ACTIVE, false)
            .set(TITLE_AVAILABILITY.SOURCE_CHECKED_AT, OffsetDateTime.now(clock))
            .where(TITLE_AVAILABILITY.ID.eq(id))
            .execute()
    }

    /**
     * Records that a still-present row was re-verified.
     *
     * This is what keeps the staleness timestamp honest: a card reading
     * "verified 2 hours ago" has to mean the check actually happened, not that
     * the row happened to be written two hours ago.
     */
    fun markVerified(ids: List<UUID>) {
        if (ids.isEmpty()) return
        dsl.update(TITLE_AVAILABILITY)
            .set(TITLE_AVAILABILITY.SOURCE_CHECKED_AT, OffsetDateTime.now(clock))
            .where(TITLE_AVAILABILITY.ID.`in`(ids))
            .execute()
    }

    /**
     * Appends to the snapshot history.
     *
     * Written on every refresh, whether or not anything changed: the absence of
     * change on a given day is data. This table is the one asset in the project
     * that cannot be re-downloaded later (spec section 14.5), which is why
     * collection starts now and the model that uses it comes much later.
     */
    fun recordSnapshot(titleId: UUID, regionCode: String, availabilityHash: String, rawSummary: Map<String, Any?>) {
        dsl.insertInto(AVAILABILITY_SNAPSHOTS)
            .set(AVAILABILITY_SNAPSHOTS.ID, UUID.randomUUID())
            .set(AVAILABILITY_SNAPSHOTS.TITLE_ID, titleId)
            .set(AVAILABILITY_SNAPSHOTS.REGION_CODE, regionCode)
            .set(AVAILABILITY_SNAPSHOTS.CAPTURED_AT, OffsetDateTime.now(clock))
            .set(AVAILABILITY_SNAPSHOTS.AVAILABILITY_HASH, availabilityHash)
            .set(AVAILABILITY_SNAPSHOTS.RAW_SUMMARY, JSONB.valueOf(objectMapper.writeValueAsString(rawSummary)))
            .execute()
    }

    fun latestSnapshotHash(titleId: UUID, regionCode: String): String? = dsl.select(AVAILABILITY_SNAPSHOTS.AVAILABILITY_HASH)
        .from(AVAILABILITY_SNAPSHOTS)
        .where(AVAILABILITY_SNAPSHOTS.TITLE_ID.eq(titleId))
        .and(AVAILABILITY_SNAPSHOTS.REGION_CODE.eq(regionCode))
        .orderBy(AVAILABILITY_SNAPSHOTS.CAPTURED_AT.desc())
        .limit(1)
        .fetchOne()
        ?.value1()
        ?.trim()

    fun countSnapshots(titleId: UUID): Int = dsl.fetchCount(AVAILABILITY_SNAPSHOTS, AVAILABILITY_SNAPSHOTS.TITLE_ID.eq(titleId))

    private companion object {
        /**
         * Referenced by name because the jOOQ generator never sees this column;
         * it is added by a fenced ALTER in V5. The type is deliberately opaque:
         * nothing in Kotlin reads a range value, it is only ever written.
         */
        val validityField = DSL.field(DSL.name("validity"), String::class.java)
    }
}
