package app.plotted.alerts.domain

import java.time.Instant
import java.util.UUID

/** One thing Plotted has to say to one person, on its way to being stored. */
data class Alert(
    val userId: UUID,
    val alertType: String,
    val severity: String,
    val titleId: UUID?,
    val message: String,
    val actionPayload: Map<String, Any?>?,
)

/**
 * A stored alert, as a screen reads it.
 *
 * A domain type rather than one hanging off the repository, so the API layer can
 * name it without importing persistence -- which `ModuleBoundaryTest` forbids,
 * and which is how a controller ends up quietly coupled to a query.
 */
data class StoredAlert(
    val id: UUID,
    val alertType: String,
    val severity: String,
    val titleId: UUID?,
    val message: String,
    val actionPayload: Map<String, Any?>?,
    val status: AlertStatus,
    val createdAt: Instant,
)

enum class AlertStatus(val dbValue: String) {
    UNREAD("unread"),
    READ("read"),
    DISMISSED("dismissed"),
    EXPIRED("expired"),
    ;

    companion object {
        fun fromDb(value: String): AlertStatus = entries.firstOrNull { it.dbValue == value } ?: error("Unknown alert status '$value'")

        fun parse(value: String): AlertStatus? = entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) }
    }
}

/**
 * The alert types Plotted can raise.
 *
 * Named constants rather than string literals at the call site, because
 * suppression matches on this value: a typo in one of two places would make
 * "have we already said this" silently answer no, forever.
 */
object AlertTypes {
    /**
     * A title on your list has left a service you pay for.
     *
     * Past tense on purpose. The nightly diff sees a title that *was* on a
     * service and now is not, which is a departure that has already happened.
     * Saying "about to leave" would be a prediction, and predicting a departure
     * needs the removal-risk model and the months of snapshot history it is
     * trained on — neither of which exists yet.
     */
    const val AVAILABILITY_LEFT = "availability.left"
}
