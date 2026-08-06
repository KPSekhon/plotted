package app.plotted.recommendation.domain

/**
 * The features the linear ranker scores on, and what each is worth.
 *
 * Weights sum to 1 across the whole set. They do not sum to 1 for any individual
 * candidate, because a candidate rarely has every feature — which is the entire
 * subject of [FeatureVector.score].
 */
enum class Feature(val weight: Double, val label: String) {
    /**
     * How much the user said they want it. The strongest signal available,
     * because unlike everything else here it is a direct statement of intent
     * rather than an inference.
     */
    PRIORITY(0.35, "how much you want it"),

    /**
     * How well it fits the time available. Asymmetric — see [runtimeFit].
     */
    RUNTIME_FIT(0.25, "fits the time you have"),

    /**
     * Whether it can be watched right now without paying again.
     */
    ACCESS(0.20, "already included in what you pay for"),

    /**
     * How close a self-imposed deadline is.
     */
    DEADLINE(0.10, "you wanted it watched by a date"),

    /**
     * What everyone else thought of it. Deliberately the smallest weight: it is
     * the only feature that says nothing about *this* person, and a recommender
     * that leans on it produces the same list for everybody.
     */
    ACCLAIM(0.10, "well regarded"),
    ;

    companion object {
        /** Sanity check for the table above; asserted by a test rather than trusted. */
        val TOTAL_WEIGHT: Double get() = entries.sumOf { it.weight }
    }
}

/**
 * One candidate's features. Absent means *unknown*, never zero.
 *
 * The distinction is the whole point. A film with no community rating is not a
 * film everyone hated, and scoring it as though it were would push every
 * obscure title to the bottom of every list — a bias that looks like taste and
 * is actually a missing join.
 */
data class FeatureVector(private val values: Map<Feature, Double>) {
    operator fun get(feature: Feature): Double? = values[feature]

    val present: Set<Feature> get() = values.keys

    /**
     * The weighted score, renormalised over the features that are actually
     * present.
     *
     * This is the two lines most implementations ship without. Without them a
     * candidate missing a 0.10-weight feature can score at most 0.90, so it
     * loses to an identical candidate that happens to have complete metadata —
     * and the ranking silently becomes a ranking of data quality. Dividing by
     * the weight actually available restores comparability.
     *
     * Returns null when nothing is known at all. A candidate with no features is
     * not a candidate scoring zero, and forcing it to the bottom would be the
     * same mistake one level up.
     */
    fun score(): Double? {
        if (values.isEmpty()) return null
        val availableWeight = values.keys.sumOf { it.weight }
        if (availableWeight <= 0.0) return null
        return values.entries.sumOf { (feature, value) -> feature.weight * value } / availableWeight
    }

    /**
     * Each feature's share of the final score, largest first.
     *
     * Explanations are rendered from this and nothing else. The rule from the
     * spec is that a reason must be a real contribution rather than prose that
     * sounds like one: if the interface says "because you rated it highly", that
     * has to be because [Feature.PRIORITY] genuinely dominated this score.
     */
    fun contributions(): List<Contribution> {
        val availableWeight = values.keys.sumOf { it.weight }
        if (availableWeight <= 0.0) return emptyList()
        return values.entries
            .map { (feature, value) ->
                Contribution(feature = feature, value = value, share = feature.weight * value / availableWeight)
            }
            .sortedByDescending { it.share }
    }

    data class Contribution(
        val feature: Feature,
        /** The raw 0..1 feature value. */
        val value: Double,
        /** How much of the final score this feature accounted for. */
        val share: Double,
    )

    companion object {
        fun of(vararg pairs: Pair<Feature, Double?>): FeatureVector =
            FeatureVector(pairs.mapNotNull { (f, v) -> v?.let { f to it.coerceIn(0.0, 1.0) } }.toMap())
    }
}

/**
 * How well a runtime fits the time available, from 0 to 1.
 *
 * **Asymmetric, deliberately.** Finishing twenty minutes early is a mild
 * disappointment; being twenty minutes short of the end when you have to stop is
 * the failure the whole product exists to prevent. Overshoot is therefore
 * penalised roughly three times as steeply as undershoot.
 *
 * Overshoot *beyond tolerance* is not penalised here at all — it is a hard
 * filter applied before scoring, because no amount of being otherwise perfect
 * makes a three-hour film fit into ninety minutes. Softening that into a penalty
 * is how a recommender ends up confidently suggesting something that does not
 * fit, which is worse than saying nothing.
 */
fun runtimeFit(watchMinutes: Int, availableMinutes: Int): Double {
    if (availableMinutes <= 0) return 0.0
    val ratio = watchMinutes.toDouble() / availableMinutes
    return when {
        ratio <= 1.0 -> 1.0 - (1.0 - ratio) * UNDERSHOOT_PENALTY
        else -> (1.0 - (ratio - 1.0) * OVERSHOOT_PENALTY).coerceAtLeast(0.0)
    }
}

/** Leaving time on the table is a small cost: an hour spare in a two-hour slot still scores 0.5. */
private const val UNDERSHOOT_PENALTY = 1.0

/** Running over is three times worse per unit, and stops being scored at all past the tolerance. */
private const val OVERSHOOT_PENALTY = 3.0

/**
 * How far past the available time a title may still be considered.
 *
 * Ten percent — the slack in "I have about an hour and a half". Past this the
 * candidate is filtered out rather than penalised.
 */
const val OVERSHOOT_TOLERANCE = 0.10
