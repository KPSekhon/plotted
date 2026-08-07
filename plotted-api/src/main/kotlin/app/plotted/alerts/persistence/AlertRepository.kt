package app.plotted.alerts.persistence

import app.plotted.alerts.domain.Alert
import app.plotted.alerts.domain.AlertStatus
import app.plotted.alerts.domain.StoredAlert
import app.plotted.generated.jooq.tables.references.ALERTS
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What Plotted has told somebody.
 *
 * `alerts` has existed since V7 with nothing writing to it. This is the writer,
 * and the read that matters most is [hasRecentAlert] — the last of Plot Armour's
 * suppression rules lives in a query rather than in the rules object, because
 * "have we already said this" is a question only the database can answer.
 */
@Repository
class AlertRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun create(alert: Alert): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(ALERTS)
            .set(ALERTS.ID, id)
            .set(ALERTS.USER_ID, alert.userId)
            .set(ALERTS.ALERT_TYPE, alert.alertType)
            .set(ALERTS.SEVERITY, alert.severity)
            .set(ALERTS.TITLE_ID, alert.titleId)
            .set(ALERTS.MESSAGE, alert.message)
            .set(
                ALERTS.ACTION_PAYLOAD,
                alert.actionPayload?.let { JSONB.valueOf(objectMapper.writeValueAsString(it)) },
            )
            .set(ALERTS.STATUS, AlertStatus.UNREAD.dbValue)
            .set(ALERTS.CREATED_AT, OffsetDateTime.now(clock))
            .execute()
        return id
    }

    /**
     * Whether this person has already been told this, recently enough to count.
     *
     * Scoped by type as well as title. "This left Crave" and "your renewal is
     * coming" are different things to say about the same film, and one should not
     * silence the other.
     *
     * Dismissed alerts still count. Somebody who dismissed a notice did not ask
     * to be told again in a week — dismissing *is* the answer, and re-sending
     * would be the behaviour that gets notifications turned off entirely.
     */
    fun hasRecentAlert(userId: UUID, titleId: UUID, alertType: String, within: Duration): Boolean = dsl.fetchExists(
        ALERTS,
        ALERTS.USER_ID.eq(userId)
            .and(ALERTS.TITLE_ID.eq(titleId))
            .and(ALERTS.ALERT_TYPE.eq(alertType))
            .and(ALERTS.CREATED_AT.ge(OffsetDateTime.now(clock).minus(within))),
    )

    /** What is waiting for someone, newest first. Read and dismissed alerts are excluded. */
    fun unread(userId: UUID, limit: Int): List<StoredAlert> = dsl.select(
        ALERTS.ID,
        ALERTS.ALERT_TYPE,
        ALERTS.SEVERITY,
        ALERTS.TITLE_ID,
        ALERTS.MESSAGE,
        ALERTS.ACTION_PAYLOAD,
        ALERTS.STATUS,
        ALERTS.CREATED_AT,
    )
        .from(ALERTS)
        .where(ALERTS.USER_ID.eq(userId))
        .and(ALERTS.STATUS.eq(AlertStatus.UNREAD.dbValue))
        .orderBy(ALERTS.CREATED_AT.desc())
        .limit(limit)
        .fetch()
        .map {
            StoredAlert(
                id = it[ALERTS.ID]!!,
                alertType = it[ALERTS.ALERT_TYPE]!!,
                severity = it[ALERTS.SEVERITY]!!,
                titleId = it[ALERTS.TITLE_ID],
                message = it[ALERTS.MESSAGE]!!,
                actionPayload = it[ALERTS.ACTION_PAYLOAD]?.let { payload ->
                    objectMapper.readValue(payload.data(), object : TypeReference<Map<String, Any?>>() {})
                },
                status = AlertStatus.fromDb(it[ALERTS.STATUS]!!),
                createdAt = it[ALERTS.CREATED_AT]!!.toInstant(),
            )
        }

    // StoredAlert lives in the domain, so the API layer can name it without
    // importing persistence.

    /** Marks one alert read or dismissed. Scoped by user, so nobody can settle another's. */
    fun setStatus(userId: UUID, alertId: UUID, status: AlertStatus): Boolean {
        val update = dsl.update(ALERTS)
            .set(ALERTS.STATUS, status.dbValue)
            .set(
                ALERTS.READ_AT,
                if (status == AlertStatus.UNREAD) null else OffsetDateTime.now(clock),
            )
            .where(ALERTS.USER_ID.eq(userId))
            .and(ALERTS.ID.eq(alertId))
        return update.execute() > 0
    }
}
