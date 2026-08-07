package app.plotted.analytics.api

import app.plotted.analytics.domain.EndCredits
import app.plotted.analytics.domain.EndCreditsService
import app.plotted.platform.security.currentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * End Credits, for the signed-in user.
 *
 * Per-user rather than global. These are the numbers that decide whether the
 * product works, and the honest place to show them is to the person whose
 * decisions produced them — a site-wide dashboard would need an authorisation
 * concept this application does not have, and inventing one to serve two numbers
 * would be the wrong order to do things in.
 */
@RestController
@RequestMapping("/api/v1/analytics/end-credits")
class EndCreditsController(
    private val endCredits: EndCreditsService,
) {
    @GetMapping
    @Operation(
        summary = "Whether Plotted actually helped",
        description =
        "Decision latency and accepted-and-completed rate. Both are null until there is enough " +
            "to compute them from, rather than defaulting to a flattering zero, and both report " +
            "what was excluded alongside what was counted.",
    )
    fun endCredits(): ResponseEntity<EndCreditsResponse> =
        ResponseEntity.ok(EndCreditsResponse.from(endCredits.forUser(currentUser().userId)))
}

@Schema(
    description =
    "The two metrics that carry Plotted's argument: does it save time, and did you watch the " +
        "thing. Everything else is decoration.",
)
data class EndCreditsResponse(
    val decisionLatency: DecisionLatencyResponse,
    val acceptedAndCompleted: CompletionRateResponse,
    val recommendationsServed: Int,
    @Schema(description = "Requests that returned nothing. Reported beside the rest: refusing is a feature.")
    val nothingFitCount: Int,
) {
    companion object {
        fun from(credits: EndCredits): EndCreditsResponse = EndCreditsResponse(
            decisionLatency = DecisionLatencyResponse(
                medianSeconds = credits.decisionLatency.median?.seconds,
                fastestSeconds = credits.decisionLatency.fastest?.seconds,
                slowestSeconds = credits.decisionLatency.slowest?.seconds,
                sampleSize = credits.decisionLatency.sampleSize,
                excludedAsStale = credits.decisionLatency.excludedAsStale,
            ),
            acceptedAndCompleted = CompletionRateResponse(
                rate = credits.acceptedAndCompleted.rate,
                completed = credits.acceptedAndCompleted.completed,
                judged = credits.acceptedAndCompleted.judged,
                tooRecentToJudge = credits.acceptedAndCompleted.tooRecentToJudge,
            ),
            recommendationsServed = credits.recommendationsServed,
            nothingFitCount = credits.nothingFitCount,
        )
    }
}

@Schema(
    description =
    "How long between being shown picks and choosing one. The median, because wall-clock has an " +
        "unbounded tail. Null until at least one acceptance qualifies — a latency computed from " +
        "nothing is not zero.",
)
data class DecisionLatencyResponse(
    val medianSeconds: Long?,
    val fastestSeconds: Long?,
    val slowestSeconds: Long?,
    val sampleSize: Int,
    @Schema(description = "Acceptances more than four hours after the request. A later session, not a slow decision.")
    val excludedAsStale: Int,
)

@Schema(
    description =
    "Of the picks you accepted, how many you finished. Acceptances newer than fourteen days are " +
        "held back rather than counted as failures, which would make the rate climb on its own " +
        "as the log aged.",
)
data class CompletionRateResponse(
    @Schema(description = "0 to 1, or null when nothing has had the chance to be finished yet.")
    val rate: Double?,
    val completed: Int,
    val judged: Int,
    val tooRecentToJudge: Int,
)
