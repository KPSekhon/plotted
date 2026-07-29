package app.plotted.platform.persistence

import app.plotted.generated.jooq.tables.references.AUDIT_LOG
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Audit trail for sensitive actions (spec section 18). Deliberately append-only:
 * there is no update or delete here, and there should never be one.
 */
@Repository
class AuditLogRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun record(entry: AuditEntry) {
        dsl.insertInto(AUDIT_LOG)
            .set(AUDIT_LOG.ACTOR_USER_ID, entry.actorUserId)
            .set(AUDIT_LOG.ACTION, entry.action)
            .set(AUDIT_LOG.RESOURCE_TYPE, entry.resourceType)
            .set(AUDIT_LOG.RESOURCE_ID, entry.resourceId)
            .set(AUDIT_LOG.BEFORE_STATE, entry.beforeState?.let(::toJsonb))
            .set(AUDIT_LOG.AFTER_STATE, entry.afterState?.let(::toJsonb))
            .set(AUDIT_LOG.IP_HASH, entry.ipHash)
            .set(AUDIT_LOG.OCCURRED_AT, OffsetDateTime.now(clock))
            .execute()
    }

    private fun toJsonb(value: Map<String, Any?>): JSONB = JSONB.valueOf(objectMapper.writeValueAsString(value))

    data class AuditEntry(
        val actorUserId: UUID?,
        val action: String,
        val resourceType: String,
        val resourceId: UUID?,
        val beforeState: Map<String, Any?>? = null,
        val afterState: Map<String, Any?>? = null,
        val ipHash: String? = null,
    )
}
