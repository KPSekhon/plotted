package app.plotted.recommendation.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The hard filters, and the "nothing fits" path they make possible.
 *
 * The rule these enforce is that a constraint is not a preference. Every one of
 * them could be softened into a penalty, and every softening would let the
 * recommender confidently suggest something that does not actually work.
 */
class HardFiltersTest {
    private val netflix = UUID.randomUUID()
    private val cbcGem = UUID.randomUUID()
    private val crave = UUID.randomUUID()

    @Test
    fun `a title longer than the time available is excluded, not merely penalised`() {
        val result = screen(
            candidate(watchMinutes = 180),
            context(availableMinutes = 90),
            blockedTitleIds = emptySet(),
            subscribedProviderIds = setOf(netflix),
        )

        // No score is high enough to make a three-hour film fit into ninety
        // minutes. Softening this into a penalty is how a recommender ends up
        // recommending something the user cannot finish.
        result.shouldBeInstanceOf<Screened.Rejected>().reason shouldBe Rejection.TOO_LONG
    }

    @Test
    fun `a little over is tolerated, because ninety minutes means about ninety`() {
        val result = screen(
            candidate(watchMinutes = 96),
            context(availableMinutes = 90),
            blockedTitleIds = emptySet(),
            subscribedProviderIds = setOf(netflix),
        )

        result.shouldBeInstanceOf<Screened.Eligible>()
    }

    @Test
    fun `an unknown runtime is disqualifying only when a time budget was given`() {
        val withBudget = screen(
            candidate(watchMinutes = null),
            context(availableMinutes = 90),
            emptySet(),
            setOf(netflix),
        )
        val withoutBudget = screen(
            candidate(watchMinutes = null),
            context(availableMinutes = null),
            emptySet(),
            setOf(netflix),
        )

        // "It fits" would be a guess, and not guessing is the promise. With no
        // budget there is nothing to fit into, so the same title is fine.
        withBudget.shouldBeInstanceOf<Screened.Rejected>().reason shouldBe Rejection.RUNTIME_UNKNOWN
        withoutBudget.shouldBeInstanceOf<Screened.Eligible>()
    }

    @Test
    fun `the default policy refuses anything needing a subscription the user does not have`() {
        val result = screen(
            candidate(providerId = crave),
            context(accessPolicy = AccessPolicy.SUBSCRIBED_ONLY),
            emptySet(),
            subscribedProviderIds = setOf(netflix),
        )

        // Recommending a service someone does not pay for is a sales pitch, not
        // an answer to "what should I watch tonight".
        result.shouldBeInstanceOf<Screened.Rejected>().reason shouldBe Rejection.ACCESS_POLICY
    }

    @Test
    fun `including free services admits a free provider but still not a paid one`() {
        val free = screen(
            candidate(providerId = cbcGem, isFree = true),
            context(accessPolicy = AccessPolicy.INCLUDE_FREE),
            emptySet(),
            setOf(netflix),
        )
        val paid = screen(
            candidate(providerId = crave, isFree = false),
            context(accessPolicy = AccessPolicy.INCLUDE_FREE),
            emptySet(),
            setOf(netflix),
        )

        free.shouldBeInstanceOf<Screened.Eligible>()
        paid.shouldBeInstanceOf<Screened.Rejected>()
    }

    @Test
    fun `surviving offers are narrowed to the ones the policy actually allows`() {
        val result = screen(
            candidate(providerId = netflix).let {
                it.copy(offers = it.offers + Candidate.Offer(crave, "Crave", isFree = false))
            },
            context(accessPolicy = AccessPolicy.SUBSCRIBED_ONLY),
            emptySet(),
            subscribedProviderIds = setOf(netflix),
        )

        // Otherwise the interface would tell someone a title is on Crave when
        // the reason it was recommended is that it is on Netflix.
        val eligible = result.shouldBeInstanceOf<Screened.Eligible>()
        eligible.candidate.offers.map { it.providerId } shouldBe listOf(netflix)
    }

    @Test
    fun `a blocked title is reported as blocked even when it also fails another filter`() {
        val titleId = UUID.randomUUID()
        val result = screen(
            candidate(watchMinutes = 300).copy(titleId = titleId),
            context(availableMinutes = 90),
            blockedTitleIds = setOf(titleId),
            subscribedProviderIds = setOf(netflix),
        )

        // Both true, but blocked is the reason the user already knows about and
        // will immediately recognise.
        result.shouldBeInstanceOf<Screened.Rejected>().reason shouldBe Rejection.BLOCKED
    }

    @Test
    fun `a title streaming nowhere is distinguished from one behind a paywall`() {
        val result = screen(
            candidate().copy(offers = emptyList()),
            context(),
            emptySet(),
            setOf(netflix),
        )

        // Different causes and different fixes: one is a gap in the catalogue,
        // the other is a subscription decision.
        result.shouldBeInstanceOf<Screened.Rejected>().reason shouldBe Rejection.NOT_AVAILABLE
    }

    private fun candidate(watchMinutes: Int? = 100, providerId: UUID = netflix, isFree: Boolean = false) = Candidate(
        titleId = UUID.randomUUID(),
        name = "A Title",
        mediaType = "movie",
        posterUrl = null,
        watchMinutes = watchMinutes,
        priority = 3,
        desiredByDate = null,
        communityRating = null,
        offers = listOf(Candidate.Offer(providerId, "Provider", isFree)),
    )

    private fun context(availableMinutes: Int? = null, accessPolicy: AccessPolicy = AccessPolicy.SUBSCRIBED_ONLY) =
        TonightContext(regionCode = "CA", availableMinutes = availableMinutes, accessPolicy = accessPolicy)
}
