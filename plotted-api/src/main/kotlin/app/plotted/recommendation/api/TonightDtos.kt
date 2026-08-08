package app.plotted.recommendation.api

import app.plotted.recommendation.domain.Pick
import app.plotted.recommendation.domain.Recommendation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(description = "Which of tonight's picks you are actually watching.")
data class AcceptPickRequest(
    @field:NotNull
    val titleId: UUID?,
)

@Schema(
    description =
    "Tonight's answer. An empty picks list with a populated diagnosis is a valid, " +
        "successful response: it means the constraints excluded everything, which is " +
        "information rather than a failure.",
)
data class TonightResponse(
    @Schema(
        description =
        "The decision this answer came from. Pass it back when accepting a pick, so the " +
            "acceptance attaches to the exact item that was offered rather than merely to a title.",
    )
    val requestId: UUID,
    val picks: List<PickResponse>,
    @Schema(description = "Present only when nothing fit.")
    val diagnosis: DiagnosisResponse?,
    @Schema(description = "How many watchlist items were considered before filtering.")
    val candidateCount: Int,
    @Schema(description = "How many survived the hard filters.")
    val eligibleCount: Int,
) {
    companion object {
        fun from(requestId: UUID, served: Recommendation.Served): TonightResponse = TonightResponse(
            requestId = requestId,
            picks = served.picks.mapIndexed { index, pick -> PickResponse.from(pick, index + 1) },
            diagnosis = null,
            candidateCount = served.candidateCount,
            eligibleCount = served.eligibleCount,
        )

        fun from(requestId: UUID, nothing: Recommendation.NothingFits): TonightResponse = TonightResponse(
            requestId = requestId,
            picks = emptyList(),
            diagnosis = DiagnosisResponse(
                headline = headlineFor(nothing),
                reasons = nothing.reasons.map { (reason, count) ->
                    RejectionResponse(reason = reason.name.lowercase(), explanation = reason.explanation, count = count)
                }.sortedByDescending { it.count },
            ),
            candidateCount = nothing.candidateCount,
            eligibleCount = 0,
        )

        /**
         * One sentence naming the constraint that did the most damage.
         *
         * Built from the counts rather than written per case, so it cannot drift
         * away from what actually happened. The advice attached to each is the
         * lever the user can actually pull.
         */
        private fun headlineFor(nothing: Recommendation.NothingFits): String {
            if (nothing.candidateCount == 0) {
                return "There is nothing on your list yet, so there is nothing to choose from."
            }
            val dominant = nothing.dominantReason
                ?: return "Nothing on your list fits what you asked for."
            return "Nothing fits: " + when (dominant.name) {
                "TOO_LONG" -> "everything on your list is longer than the time you have."
                "RUNTIME_UNKNOWN" -> "Plotted does not know how long these are yet, so it will not promise they fit."
                "ACCESS_POLICY" -> "what is left is only on services you are not paying for."
                "NOT_AVAILABLE" -> "nothing on your list is streaming in your region right now."
                "BLOCKED" -> "everything left is something you asked not to be shown."
                else -> dominant.explanation
            }
        }
    }
}

data class PickResponse(
    @Schema(description = "1 is the pick, 2 and 3 are backups.")
    val position: Int,
    val titleId: UUID,
    val name: String,
    val mediaType: String,
    val posterUrl: String?,
    @Schema(
        description =
        "The whole commitment: a film's runtime, or every episode of a series added up. Shown " +
            "so somebody can see what they are taking on. Not what the time filter used.",
    )
    val watchMinutes: Int?,
    @Schema(
        description =
        "One sitting: a film, or a typical episode. This is what the time budget was measured " +
            "against, because a long series is watched in increments rather than in one go.",
    )
    val sessionMinutes: Int?,
    @Schema(description = "True when sessionMinutes describes an episode rather than the whole title.")
    val perEpisode: Boolean,
    @Schema(description = "Where it can be watched, under the access policy that was applied.")
    val availableOn: List<String>,
    @Schema(description = "0 to 1, after renormalising over the features this title actually has.")
    val score: Double,
    @Schema(
        description =
        "Why it was chosen, largest contribution first. Derived from the scored features " +
            "themselves — never generated prose.",
    )
    val reasons: List<ReasonResponse>,
    @Schema(
        description =
        "True when this slot was filled by exploration rather than by rank. Surfaced rather " +
            "than hidden: a suggestion that is deliberately a wildcard should say so.",
    )
    val exploration: Boolean,
) {
    companion object {
        fun from(pick: Pick, position: Int): PickResponse = PickResponse(
            position = position,
            titleId = pick.candidate.titleId,
            name = pick.candidate.name,
            mediaType = pick.candidate.mediaType,
            posterUrl = pick.candidate.posterUrl,
            watchMinutes = pick.candidate.watchMinutes,
            sessionMinutes = pick.candidate.sessionMinutes,
            perEpisode = pick.candidate.mediaType != "movie",
            availableOn = pick.candidate.offers.map { it.providerName }.distinct().sorted(),
            score = pick.score,
            // Only contributions that meaningfully moved the score. A reason
            // worth 2% of the answer is noise presented as insight.
            reasons = pick.features.contributions()
                .filter { it.share >= MINIMUM_REPORTABLE_SHARE }
                .map { ReasonResponse(it.feature.label, it.share) },
            exploration = pick.exploration,
        )

        private const val MINIMUM_REPORTABLE_SHARE = 0.05
    }
}

data class ReasonResponse(
    val reason: String,
    @Schema(description = "How much of the score this accounted for, 0 to 1.")
    val share: Double,
)

data class DiagnosisResponse(
    val headline: String,
    val reasons: List<RejectionResponse>,
)

data class RejectionResponse(
    val reason: String,
    val explanation: String,
    val count: Int,
)
