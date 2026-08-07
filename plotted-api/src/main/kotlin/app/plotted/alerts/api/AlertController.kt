package app.plotted.alerts.api

import app.plotted.alerts.domain.AlertService
import app.plotted.alerts.domain.AlertStatus
import app.plotted.alerts.domain.StoredAlert
import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.security.currentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * What Plotted has to say to the signed-in user.
 *
 * Unread only. An alert that has been read or dismissed has done its job, and a
 * list that keeps showing them is a list people stop opening.
 */
@RestController
@RequestMapping("/api/v1/alerts")
class AlertController(
    private val alerts: AlertService,
) {
    @GetMapping
    @Operation(
        summary = "Unread alerts for the signed-in user",
        description = "Newest first. Read and dismissed alerts are not returned.",
    )
    fun list(): ResponseEntity<AlertsResponse> =
        ResponseEntity.ok(AlertsResponse(alerts.unread(currentUser().userId).map(AlertResponse::from)))

    @PatchMapping("/{alertId}")
    @Operation(
        summary = "Mark an alert read or dismissed",
        description =
        "Dismissing also silences repeats about the same title for a while: dismissing is an " +
            "answer, and re-sending would be the behaviour that gets notifications turned off.",
    )
    fun update(@PathVariable alertId: UUID, @Valid @RequestBody request: UpdateAlertRequest): ResponseEntity<Void> {
        val status = AlertStatus.parse(requireNotNull(request.status)) ?: throw ApiException(
            ErrorCode.VALIDATION_FAILED,
            "Unknown status '${request.status}'",
            mapOf("status" to "Must be one of: read, dismissed"),
        )
        if (status == AlertStatus.UNREAD || status == AlertStatus.EXPIRED) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "An alert can only be marked read or dismissed",
                mapOf("status" to "Must be one of: read, dismissed"),
            )
        }

        alerts.settle(currentUser().userId, alertId, status)
        return ResponseEntity.noContent().build()
    }
}

data class AlertsResponse(val alerts: List<AlertResponse>)

@Schema(description = "Something Plotted needs to tell you.")
data class AlertResponse(
    val id: UUID,
    @Schema(description = "For example availability.left.")
    val alertType: String,
    @Schema(allowableValues = ["info", "warning", "urgent"])
    val severity: String,
    val titleId: UUID?,
    val message: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(alert: StoredAlert): AlertResponse = AlertResponse(
            id = alert.id,
            alertType = alert.alertType,
            severity = alert.severity,
            titleId = alert.titleId,
            message = alert.message,
            createdAt = alert.createdAt,
        )
    }
}

data class UpdateAlertRequest(
    @field:NotBlank
    @Schema(allowableValues = ["read", "dismissed"])
    val status: String?,
)
