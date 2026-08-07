package app.plotted.recommendation.evaluation

import app.plotted.recommendation.domain.AccessPolicy
import app.plotted.recommendation.domain.Candidate
import app.plotted.recommendation.domain.Ranker
import app.plotted.recommendation.domain.TonightContext
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/**
 * The one experiment that can be run before a single user exists.
 *
 * ### The question
 *
 * `FeatureVector.score()` divides the weighted sum by the weight *actually
 * present* on a candidate. Remove that and a candidate missing a 0.10-weight
 * feature caps at 0.90, so it loses to an otherwise identical candidate with
 * complete metadata. The claim in the code comments is that this matters. This
 * measures how much.
 *
 * ### The design
 *
 * Each candidate is generated **complete** — every one of the five features
 * populated — and its true quality is defined as the score the shipped ranker
 * gives that complete vector. Metadata is then *censored*: fields are blanked at
 * random, independently of quality, and the ranker sees only what survives.
 * Relevance is graded and comes from the uncensored score.
 *
 * So the question being answered is precise: **given that some candidates have
 * missing metadata, how much ranking quality does renormalisation recover?**
 *
 * ### What this is not
 *
 * It is not evidence that users prefer the ranker's ordering. There is no user
 * in it. The ground truth is the model's own opinion given complete data, so
 * this measures robustness to missing metadata and nothing else — a
 * self-consistency property, and a real one, but not a claim about taste.
 *
 * It also censors **independently of quality**, which is the conservative
 * assumption and not quite the world: obscure titles are both more likely to
 * lack metadata and less likely to be wanted. Where those correlate, the
 * un-normalised scorer would be accidentally right some of the time, so the real
 * effect is probably smaller than the number this reports. Said here rather than
 * discovered later.
 */
class MetadataCensoringSimulation(
    private val queries: Int = DEFAULT_QUERIES,
    private val candidatesPerQuery: Int = DEFAULT_CANDIDATES,
    /** Chance that any one optional field is blanked. */
    private val censorRate: Double = DEFAULT_CENSOR_RATE,
    private val seed: Long = DEFAULT_SEED,
) {
    private val ranker = Ranker()
    private val today = LocalDate.of(2026, 8, 6)

    fun generate(): List<EvaluationQuery> {
        val random = Random(seed)
        return (0 until queries).map { queryIndex ->
            val availableMinutes = random.nextInt(60, 200)
            val context = TonightContext("CA", availableMinutes, AccessPolicy.SUBSCRIBED_ONLY)
            val provider = random.nextUuid()

            val complete = (0 until candidatesPerQuery).map { completeCandidate(random, provider) }

            // Truth first, from the uncensored candidate, using the very scorer
            // under test. Both strategies are then handicapped identically —
            // what differs between them is only how they cope with the gaps.
            val relevance = complete.associate { candidate ->
                candidate.titleId to (ranker.score(candidate, context, setOf(provider), today)?.score ?: 0.0)
            }

            EvaluationQuery(
                queryId = "sim-$queryIndex",
                candidates = complete.map { censor(it, random) },
                context = context,
                subscribedProviderIds = setOf(provider),
                askedOn = today.minusDays(random.nextLong(0, SPREAD_DAYS)),
                relevance = relevance,
            )
        }
    }

    /**
     * Ids from the seeded stream, never [UUID.randomUUID].
     *
     * Found the hard way: every strategy breaks ties on title id, so random ids
     * meant the report's confidence intervals moved in the third decimal place
     * between runs — while the code comments claimed the whole thing was seeded
     * and reproducible. A document quoting figures that drift is a document that
     * is wrong shortly after it is written, and `EvaluationReportTest` now fails
     * the build if two runs disagree.
     */
    private fun Random.nextUuid(): UUID = UUID(nextLong(), nextLong())

    private fun completeCandidate(random: Random, provider: UUID): Candidate = Candidate(
        titleId = random.nextUuid(),
        name = "Simulated title",
        mediaType = if (random.nextBoolean()) "movie" else "series",
        posterUrl = null,
        watchMinutes = random.nextInt(70, 190),
        priority = random.nextInt(1, 6),
        // Inside the ranker's 30-day deadline horizon often enough that the
        // feature actually varies; beyond it, it contributes nothing and would
        // be a constant.
        desiredByDate = today.plusDays(random.nextLong(1, 40)),
        communityRating = random.nextDouble(4.0, 9.5),
        offers = listOf(Candidate.Offer(provider, "Simulated service", isFree = false)),
    )

    /**
     * Blanks optional fields at random.
     *
     * `priority` is never censored: it is a value the user typed and is always
     * present in the real system, so blanking it would be simulating a state
     * that cannot occur. Leaving one feature always present also keeps every
     * candidate scorable, which stops the comparison from quietly becoming one
     * about how many candidates each strategy dropped.
     */
    private fun censor(candidate: Candidate, random: Random): Candidate = candidate.copy(
        watchMinutes = candidate.watchMinutes.takeUnless { random.nextDouble() < censorRate },
        desiredByDate = candidate.desiredByDate.takeUnless { random.nextDouble() < censorRate },
        communityRating = candidate.communityRating.takeUnless { random.nextDouble() < censorRate },
        offers = if (random.nextDouble() < censorRate) emptyList() else candidate.offers,
    )

    companion object {
        const val DEFAULT_QUERIES = 2_000
        const val DEFAULT_CANDIDATES = 12
        const val DEFAULT_CENSOR_RATE = 0.30
        const val DEFAULT_SEED = 20260806L

        /** Queries are spread over a period so the temporal split has something to split. */
        const val SPREAD_DAYS = 120L
    }
}
