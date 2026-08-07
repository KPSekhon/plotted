package app.plotted.alerts.domain

import app.plotted.alerts.persistence.AlertRepository
import app.plotted.platform.error.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Alerts, as a screen sees them.
 *
 * Thin, and it exists so no controller talks to a repository -- the same rule
 * `CatalogueQueryService` follows, enforced by `ModuleBoundaryTest`.
 */
@Service
class AlertService(
    private val alerts: AlertRepository,
) {
    @Transactional(readOnly = true)
    fun unread(userId: UUID): List<StoredAlert> = alerts.unread(userId, MAX_ALERTS)

    /**
     * Marks one alert read or dismissed.
     *
     * Scoped by user in the query, so settling somebody else's alert is not a
     * check that could be forgotten -- it simply matches no rows and 404s.
     */
    @Transactional
    fun settle(userId: UUID, alertId: UUID, status: AlertStatus) {
        if (!alerts.setStatus(userId, alertId, status)) throw NotFoundException("Alert")
    }

    private companion object {
        /** Somebody with more waiting than this has a problem paging will not fix. */
        const val MAX_ALERTS = 50
    }
}
