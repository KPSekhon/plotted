package app.plotted.preferences.api

import app.plotted.preferences.domain.PilotOption
import app.plotted.preferences.domain.PilotQuestion
import app.plotted.preferences.domain.PilotState
import app.plotted.preferences.domain.PreferenceProfile
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(
    description =
    "Where the signed-in user is in the questionnaire. `question` is null when there is nothing " +
        "left to ask, and `exhausted` says which kind of nothing: a finished questionnaire, or a " +
        "catalogue too thin to supply another contrasting pair.",
)
data class PilotStateResponse(
    val question: PilotQuestionResponse?,
    @Schema(description = "Answers that count as evidence. Skipped questions are not among them.")
    val answered: Int,
    @Schema(description = "Questions declined. Recorded so they are not asked again, and excluded from the fit.")
    val skipped: Int,
    val total: Int,
    val complete: Boolean,
    @Schema(
        description =
        "True when the ladder ran out of usable pairs before reaching `total`. The catalogue is " +
            "too small or too uniform, which is a different problem from a finished questionnaire.",
    )
    val exhausted: Boolean,
) {
    companion object {
        fun from(state: PilotState): PilotStateResponse = PilotStateResponse(
            question = state.question?.let(PilotQuestionResponse::from),
            answered = state.answered,
            skipped = state.skipped,
            total = state.total,
            complete = state.complete,
            exhausted = state.exhausted,
        )
    }
}

data class PilotQuestionResponse(
    val left: PilotOptionResponse,
    val right: PilotOptionResponse,
    @Schema(description = "The axis this pair was chosen to isolate. Shown for transparency, not required to answer.")
    val axis: String,
    val axisLabel: String,
    val position: Int,
) {
    companion object {
        fun from(question: PilotQuestion): PilotQuestionResponse = PilotQuestionResponse(
            left = PilotOptionResponse.from(question.left),
            right = PilotOptionResponse.from(question.right),
            axis = question.axis.name,
            axisLabel = question.axis.label,
            position = question.position,
        )
    }
}

data class PilotOptionResponse(
    val titleId: UUID,
    val name: String,
    val mediaType: String,
    val releaseYear: Int?,
    val posterUrl: String?,
) {
    companion object {
        fun from(option: PilotOption): PilotOptionResponse = PilotOptionResponse(
            titleId = option.titleId,
            name = option.name,
            mediaType = option.mediaType,
            releaseYear = option.releaseYear,
            posterUrl = option.posterUrl,
        )
    }
}

@Schema(
    description =
    "An answer. Omit `chosenTitleId` to skip: a forced choice between two titles somebody has not " +
        "seen is a coin flip, and it is recorded as declined rather than as a preference.",
)
data class PilotAnswerRequest(
    @field:NotNull
    val leftTitleId: UUID?,
    @field:NotNull
    val rightTitleId: UUID?,
    @Schema(description = "The title picked. Null or absent means skip.")
    val chosenTitleId: UUID? = null,
)

@Schema(
    description =
    "A fitted taste profile. Null until at least one question has been answered — a profile fitted " +
        "from no answers is the population's rather than yours, and reporting it would present the " +
        "prior back as a discovery.",
)
data class PreferenceProfileResponse(
    val observations: Int,
    val converged: Boolean,
    @Schema(description = "Whether the profile has anything it can defend saying. When false, ranking ignores it.")
    val informative: Boolean,
    @Schema(description = "Every axis, including the ones with no verdict. What was not found is part of the answer.")
    val axes: List<AxisOpinionResponse>,
) {
    companion object {
        fun from(profile: PreferenceProfile): PreferenceProfileResponse = PreferenceProfileResponse(
            observations = profile.observations,
            converged = profile.converged,
            informative = profile.isInformative,
            axes = profile.opinions.map {
                AxisOpinionResponse(
                    axis = it.axis.name,
                    label = it.axis.label,
                    positive = it.axis.positive,
                    negative = it.axis.negative,
                    weight = it.weight,
                    standardError = it.standardError,
                    verdict = it.verdict.name,
                    stated = it.verdict.isDirectional,
                    sentence = it.sentence,
                )
            },
        )
    }
}

@Schema(
    description =
    "One axis. NO_PREFERENCE and NOT_ASKED are both ways of saying we do not know, and they are " +
        "not interchangeable: the first means we asked and you were balanced, the second that the " +
        "ladder never contrasted this axis, so the weight is the population's rather than yours.",
)
data class AxisOpinionResponse(
    val axis: String,
    val label: String,
    val positive: String,
    val negative: String,
    val weight: Double,
    @Schema(description = "Posterior standard deviation. What separates a finding from an unasked question.")
    val standardError: Double,
    @Schema(allowableValues = ["LIKES", "DISLIKES", "NO_PREFERENCE", "NOT_ASKED"])
    val verdict: String,
    val stated: Boolean,
    val sentence: String,
)
