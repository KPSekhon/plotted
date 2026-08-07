package app.plotted.recommendation.api

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.error.NotFoundException
import app.plotted.platform.security.currentUser
import app.plotted.recommendation.domain.AccessPolicy
import app.plotted.recommendation.domain.Recommendation
import app.plotted.recommendation.domain.TonightService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tonight")
class TonightController(
    private val tonight: TonightService,
) {
    @GetMapping
    @Operation(
        summary = "What to watch tonight",
        description =
        "One pick and two backups, each with the reasons that actually produced its score. " +
            "Returns 200 with an empty picks list and a diagnosis when nothing fits: the " +
            "constraints were the request, so relaxing them silently to produce something " +
            "would be answering a different question.",
    )
    fun tonight(
        @Parameter(description = "Minutes available. Omit for no time limit — which is not the same as zero.")
        @RequestParam(required = false) availableMinutes: Int?,
        @Parameter(description = "One of active_subscriptions_only, include_free, any_subscription.")
        @RequestParam(required = false) accessPolicy: String?,
    ): ResponseEntity<TonightResponse> {
        availableMinutes?.let {
            if (it <= 0) {
                throw ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "availableMinutes must be positive",
                    // Omitting the parameter is how you say "no limit". Zero is a
                    // different claim and almost certainly a mistake.
                    mapOf("availableMinutes" to "Must be greater than zero, or omitted for no limit"),
                )
            }
        }

        val policy = accessPolicy?.let {
            AccessPolicy.parse(it) ?: throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Unknown access policy '$it'",
                mapOf("accessPolicy" to "Must be one of: ${AccessPolicy.entries.joinToString(", ") { p -> p.dbValue }}"),
            )
        } ?: AccessPolicy.SUBSCRIBED_ONLY

        val outcome = tonight.recommend(
            userId = currentUser().userId,
            request = TonightService.TonightRequest(availableMinutes = availableMinutes, accessPolicy = policy),
        )

        return ResponseEntity.ok(
            when (val result = outcome.recommendation) {
                is Recommendation.Served -> TonightResponse.from(outcome.requestId, result)
                is Recommendation.NothingFits -> TonightResponse.from(outcome.requestId, result)
            },
        )
    }

    @PostMapping("/{requestId}/accept")
    @Operation(
        summary = "Record that you are watching one of tonight's picks",
        description =
        "Attaches the choice to the exact item that was offered, which is what makes it usable " +
            "for evaluation: the position and the propensity travel with it. Accepting a title " +
            "that was not among the picks, or someone else's recommendation, is a 404. " +
            "Only the first acceptance counts — the second click is not a second decision.",
    )
    fun accept(@PathVariable requestId: UUID, @Valid @RequestBody request: AcceptPickRequest): ResponseEntity<Void> {
        val accepted = tonight.accept(
            userId = currentUser().userId,
            requestId = requestId,
            titleId = requireNotNull(request.titleId),
        )
        if (!accepted) throw NotFoundException("Recommendation pick")
        return ResponseEntity.noContent().build()
    }
}
