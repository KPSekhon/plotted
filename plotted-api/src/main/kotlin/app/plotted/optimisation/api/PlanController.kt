package app.plotted.optimisation.api

import app.plotted.optimisation.domain.CancelCultureService
import app.plotted.optimisation.domain.PlanWeights
import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.ratelimit.RateLimitGuard
import app.plotted.platform.ratelimit.RateLimits
import app.plotted.platform.security.currentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/plan")
class PlanController(
    private val cancelCulture: CancelCultureService,
    private val rateLimit: RateLimitGuard,
) {
    @GetMapping
    @Operation(
        summary = "Which subscriptions to keep, month by month",
        description =
        "A constraint-optimised subscription plan over the next few months, with the objective " +
            "recomputed exactly by an independent checker rather than read out of the solver. " +
            "Returns 200 with an empty plan and a diagnosis when the limits cannot all be met: " +
            "the limits were the request, so quietly relaxing one would be answering a different " +
            "question. Titles that are free to watch, never checked, or only on services with no " +
            "established price are excluded from the model and reported separately.",
    )
    @Suppress("LongParameterList")
    fun plan(
        @Parameter(description = "Months to plan over, 1 to 12. Defaults to 6.")
        @RequestParam(required = false) horizonMonths: Int?,
        @Parameter(description = "Most you are willing to pay in any one month, in cents.")
        @RequestParam(required = false) maximumMonthlyCents: Long?,
        @Parameter(description = "Most services to hold at once.")
        @RequestParam(required = false) maximumActiveServices: Int?,
        @Parameter(description = "Most signups plus cancellations in any one month.")
        @RequestParam(required = false) maximumMonthlySwitches: Int?,
        @Parameter(description = "How much coverage matters, 0 to 1. Cost and churn take the remainder in a 3.5:1 ratio.")
        @RequestParam(required = false) coverageWeight: Double?,
    ): ResponseEntity<PlanResponse> {
        // Per account, and fails open. The worst case here is four CP-SAT solves
        // at a five-second cap, so a burst is genuinely expensive -- but the
        // endpoint is authenticated, so one account is the blast radius, and
        // losing the headline feature because Redis blinked would cost more than
        // the CPU does.
        rateLimit.check(RateLimits.PLAN, currentUser().userId.toString())

        val horizon = horizonMonths ?: CancelCultureService.PlanOptions.DEFAULT_HORIZON_MONTHS
        if (horizon < 1 || horizon > CancelCultureService.PlanOptions.MAXIMUM_HORIZON_MONTHS) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "horizonMonths must be between 1 and ${CancelCultureService.PlanOptions.MAXIMUM_HORIZON_MONTHS}",
                mapOf("horizonMonths" to "Must be between 1 and ${CancelCultureService.PlanOptions.MAXIMUM_HORIZON_MONTHS}"),
            )
        }

        positive("maximumMonthlyCents", maximumMonthlyCents)
        positive("maximumActiveServices", maximumActiveServices?.toLong())
        // Zero switches is a real request — "tell me the best plan that changes
        // nothing" — so this one is allowed to be zero and only rejected below it.
        maximumMonthlySwitches?.let {
            if (it < 0) {
                throw ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "maximumMonthlySwitches cannot be negative",
                    mapOf("maximumMonthlySwitches" to "Must be zero or more"),
                )
            }
        }

        val report = cancelCulture.plan(
            userId = currentUser().userId,
            options = CancelCultureService.PlanOptions(
                horizonMonths = horizon,
                maximumMonthlyCents = maximumMonthlyCents,
                maximumActiveServices = maximumActiveServices,
                maximumMonthlySwitches = maximumMonthlySwitches,
                weights = weights(coverageWeight),
            ),
        )
        return ResponseEntity.ok(PlanResponse.from(report))
    }

    /**
     * One dial rather than three.
     *
     * Three independent weights that must sum to 1 is a form nobody fills in
     * correctly, and the interesting axis is only ever "how much do I care about
     * seeing my list versus paying for it". Cost and churn split the remainder
     * in the same ratio the defaults use, so moving the dial cannot produce a
     * combination that does not sum to 1.
     */
    private fun weights(coverageWeight: Double?): PlanWeights {
        if (coverageWeight == null) return PlanWeights.DEFAULT
        if (coverageWeight < 0.0 || coverageWeight > 1.0) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "coverageWeight must be between 0 and 1",
                mapOf("coverageWeight" to "Must be between 0 and 1"),
            )
        }
        val remainder = 1.0 - coverageWeight
        val costShare = PlanWeights.DEFAULT.cost / (PlanWeights.DEFAULT.cost + PlanWeights.DEFAULT.switching)
        val cost = remainder * costShare
        // Subtracted rather than computed, so the three always sum to exactly 1
        // however the floating-point arithmetic above lands. PlanWeights rejects
        // anything else, and it should stay able to.
        return PlanWeights(coverage = coverageWeight, cost = cost, switching = remainder - cost)
    }

    private fun positive(field: String, value: Long?) {
        value?.let {
            if (it <= 0) {
                throw ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "$field must be positive",
                    mapOf(field to "Must be greater than zero, or omitted for no limit"),
                )
            }
        }
    }
}
