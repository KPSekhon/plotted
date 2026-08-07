package app.plotted.preferences.domain

import java.time.Instant
import java.util.UUID

/**
 * One answered comparison as it was stored.
 *
 * [attributeDifference] is chosen minus rejected, frozen when the person
 * answered rather than recomputed now. The RECENCY axis is measured against the
 * current year, so re-deriving an old answer would quietly restate what somebody
 * said in 2026 in the language of 2029 — and a title since removed from the
 * catalogue would take its evidence with it.
 */
data class AnsweredComparison(
    val axis: String,
    val chosenTitleId: UUID,
    val attributeDifference: DoubleArray,
    val answeredAt: Instant,
) {
    // Value semantics, because the class wraps an array and tests compare fixtures.
    override fun equals(other: Any?): Boolean = this === other || (
        other is AnsweredComparison &&
            axis == other.axis &&
            chosenTitleId == other.chosenTitleId &&
            attributeDifference.contentEquals(other.attributeDifference) &&
            answeredAt == other.answeredAt
        )

    override fun hashCode(): Int {
        var result = axis.hashCode()
        result = 31 * result + chosenTitleId.hashCode()
        result = 31 * result + attributeDifference.contentHashCode()
        return 31 * result + answeredAt.hashCode()
    }
}

/**
 * Where somebody is in the questionnaire.
 *
 * [question] is null when there is nothing left to ask — either because the
 * ladder is finished or because the catalogue cannot supply another contrasting
 * pair. Those are different situations and [exhausted] is what tells them apart:
 * a finished questionnaire is a success and an exhausted catalogue is a thin
 * seed, and showing the same "all done" screen for both would hide the second.
 */
data class PilotState(
    val question: PilotQuestion?,
    val answered: Int,
    val skipped: Int,
    val total: Int,
    val exhausted: Boolean,
) {
    val complete: Boolean get() = question == null
}

/** One question, ready to render. */
data class PilotQuestion(
    val left: PilotOption,
    val right: PilotOption,
    val axis: TasteAxis,
    /** Which question this is, 1-based, for a progress line that counts up rather than down. */
    val position: Int,
)

data class PilotOption(
    val titleId: UUID,
    val name: String,
    val mediaType: String,
    val releaseYear: Int?,
    val posterUrl: String?,
)
