package app.plotted.platform.outbox

import app.plotted.generated.jooq.tables.references.OUTBOX
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Transactional outbox.
 *
 * A domain change and the workflow or notification it triggers are two writes to
 * two systems. Doing them separately means a crash in between leaves them
 * disagreeing -- a cancelled subscription with no recalculation, or two
 * recalculations for one cancellation. Writing the event into the same Postgres
 * transaction as the change, and publishing it afterwards from a poller, makes
 * that impossible.
 *
 * The relay that drains this table arrives with the Temporal workers in Phase 10.
 * The table and this writer exist now because retrofitting an outbox means
 * rewriting every write path that should have used one.
 */
@Repository
class OutboxRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /**
     * Must be called inside the same transaction as the change it describes.
     * That is the entire point; publishing from a separate transaction would be
     * the dual write this class exists to avoid.
     */
    fun append(event: OutboxEvent): Long = dsl.insertInto(OUTBOX)
        .set(OUTBOX.AGGREGATE_TYPE, event.aggregateType)
        .set(OUTBOX.AGGREGATE_ID, event.aggregateId)
        .set(OUTBOX.EVENT_TYPE, event.eventType)
        .set(OUTBOX.PAYLOAD, JSONB.valueOf(objectMapper.writeValueAsString(event.payload)))
        .set(OUTBOX.CREATED_AT, OffsetDateTime.now(clock))
        .returningResult(OUTBOX.ID)
        .fetchOne()
        ?.value1()
        ?: error("Outbox insert returned no identifier")

    data class OutboxEvent(
        val aggregateType: String,
        val aggregateId: UUID,
        val eventType: String,
        val payload: Map<String, Any?>,
    )
}
