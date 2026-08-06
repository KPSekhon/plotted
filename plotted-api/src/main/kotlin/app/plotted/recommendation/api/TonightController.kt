package app.plotted.recommendation.api

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.security.currentUser
import app.plotted.recommendation.domain.AccessPolicy
import app.plotted.recommendation.domain.Recommendation
import app.plotted.recommendation.domain.TonightService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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

        val result = tonight.recommend(
            userId = currentUser().userId,
            request = TonightService.TonightRequest(availableMinutes = availableMinutes, accessPolicy = policy),
        )

        return ResponseEntity.ok(
            when (result) {
                is Recommendation.Served -> TonightResponse.from(result)
                is Recommendation.NothingFits -> TonightResponse.from(result)
            },
        )
    }
}
