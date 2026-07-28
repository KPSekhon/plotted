package app.plotted.catalogue.domain

import app.plotted.catalogue.persistence.ProviderRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Every case below uses provider names and identifiers taken from TMDB's real
 * response for region CA, not invented ones. The duplication these tests remove
 * is duplication that actually occurs.
 */
class ProviderResolverTest {
    private val crave = Provider(UUID.randomUUID(), "Crave", "crave", ProviderType.SUBSCRIPTION)
    private val netflix = Provider(UUID.randomUUID(), "Netflix", "netflix", ProviderType.SUBSCRIPTION)
    private val paramount = Provider(UUID.randomUUID(), "Paramount+", "paramount-plus", ProviderType.SUBSCRIPTION)
    private val prime = Provider(UUID.randomUUID(), "Amazon Prime Video", "prime-video", ProviderType.SUBSCRIPTION)
    private val appleStore = Provider(UUID.randomUUID(), "Apple TV (Store)", "apple-tv-store", ProviderType.TRANSACTIONAL)
    private val outtv = Provider(UUID.randomUUID(), "OUTtv", "outtv", ProviderType.SUBSCRIPTION)

    private val repository = mockk<ProviderRepository>()

    private fun resolverWith(map: Map<Int, Provider>): ProviderResolver {
        every { repository.loadAliasMap() } returns map
        return ProviderResolver(repository)
    }

    @Test
    fun `a service and its Amazon channel are one subscription, not two`() {
        val resolver = resolverWith(mapOf(230 to crave, 2604 to crave))

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(230, "Crave", AccessType.SUBSCRIPTION),
                RawProviderOffer(2604, "Crave Amazon Channel", AccessType.SUBSCRIPTION),
            ),
        )

        resolution.offers.size shouldBe 1
        resolution.offers.single().provider shouldBe crave
        // Provenance points at the service, not the reseller.
        resolution.offers.single().sourceTmdbProviderId shouldBe 230
    }

    @Test
    fun `keeps the direct listing even when the reseller comes first`() {
        val resolver = resolverWith(mapOf(230 to crave, 2604 to crave))

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(2604, "Crave Amazon Channel", AccessType.SUBSCRIPTION),
                RawProviderOffer(230, "Crave", AccessType.SUBSCRIPTION),
            ),
        )

        resolution.offers.single().sourceName shouldBe "Crave"
    }

    @Test
    fun `ad tiers and profile views collapse onto the one Netflix subscription`() {
        val resolver = resolverWith(mapOf(8 to netflix, 1796 to netflix, 175 to netflix))

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(8, "Netflix", AccessType.SUBSCRIPTION),
                RawProviderOffer(1796, "Netflix Standard with Ads", AccessType.SUBSCRIPTION),
                RawProviderOffer(175, "Netflix Kids", AccessType.SUBSCRIPTION),
            ),
        )

        resolution.offers.size shouldBe 1
        resolution.offers.single().sourceName shouldBe "Netflix"
    }

    @Test
    fun `all five Paramount Plus listings collapse to one`() {
        val resolver = resolverWith(
            mapOf(531 to paramount, 582 to paramount, 1853 to paramount, 2304 to paramount, 2303 to paramount),
        )

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(582, "Paramount+ Amazon Channel", AccessType.SUBSCRIPTION),
                RawProviderOffer(1853, "Paramount Plus Apple TV Channel", AccessType.SUBSCRIPTION),
                RawProviderOffer(2304, "Paramount Plus Basic with Ads", AccessType.SUBSCRIPTION),
                RawProviderOffer(2303, "Paramount Plus Premium", AccessType.SUBSCRIPTION),
                RawProviderOffer(531, "Paramount Plus", AccessType.SUBSCRIPTION),
            ),
        )

        // Left uncollapsed, this one title would count five times towards
        // coverage and the optimiser would see five services to choose from.
        resolution.offers.size shouldBe 1
        resolution.offers.single().provider shouldBe paramount
    }

    @Test
    fun `a provider listed as both subscription and free keeps the more demanding claim`() {
        // TMDB really does return this for Fleabag and Letterkenny.
        val resolver = resolverWith(mapOf(119 to prime))

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(119, "Amazon Prime Video", AccessType.SUBSCRIPTION),
                RawProviderOffer(119, "Amazon Prime Video", AccessType.FREE),
            ),
        )

        resolution.offers.size shouldBe 1
        // Telling someone a title is free when it needs a subscription is the
        // failure that destroys trust; the reverse is a pleasant surprise.
        resolution.offers.single().accessType shouldBe AccessType.SUBSCRIPTION
    }

    @Test
    fun `rent and buy on the same storefront stay separate offers`() {
        val resolver = resolverWith(mapOf(2 to appleStore))

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(2, "Apple TV Store", AccessType.RENT),
                RawProviderOffer(2, "Apple TV Store", AccessType.BUY),
            ),
        )

        // Genuinely different transactions at different prices.
        resolution.offers.map { it.accessType } shouldContainExactlyInAnyOrder
            listOf(AccessType.RENT, AccessType.BUY)
    }

    @Test
    fun `a subscription and a rental of the same title are both kept`() {
        val resolver = resolverWith(mapOf(230 to crave, 2 to appleStore))

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(230, "Crave", AccessType.SUBSCRIPTION),
                RawProviderOffer(2, "Apple TV Store", AccessType.RENT),
            ),
        )

        // Side Quest needs both: the included option and what it would cost
        // otherwise.
        resolution.offers.size shouldBe 2
    }

    @Test
    fun `a service that only exists as reseller channels still resolves`() {
        // OUTtv has no direct TMDB entry in Canada, only Amazon and Apple channels.
        val resolver = resolverWith(mapOf(607 to outtv, 2044 to outtv))

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(607, "OUTtv Amazon Channel", AccessType.SUBSCRIPTION),
                RawProviderOffer(2044, "OUTtv Apple TV Channel", AccessType.SUBSCRIPTION),
            ),
        )

        resolution.offers.size shouldBe 1
        resolution.offers.single().provider shouldBe outtv
    }

    @Test
    fun `an unmapped provider is reported rather than silently dropped`() {
        val resolver = resolverWith(mapOf(230 to crave))

        val resolution = resolver.resolve(
            listOf(
                RawProviderOffer(230, "Crave", AccessType.SUBSCRIPTION),
                RawProviderOffer(9999, "Some New Service", AccessType.SUBSCRIPTION),
            ),
        )

        resolution.offers.size shouldBe 1
        resolution.hasGaps shouldBe true
        // A mapping gap removes real availability, so it has to be visible.
        resolution.unmapped.map { it.providerName } shouldContainExactly listOf("Some New Service")
    }

    @Test
    fun `no providers is an empty answer, not a failure`() {
        val resolver = resolverWith(mapOf(230 to crave))

        val resolution = resolver.resolve(emptyList())

        resolution.offers.shouldContainExactly(emptyList())
        resolution.hasGaps shouldBe false
    }

    @Test
    fun `the alias map is loaded once and reused across titles`() {
        val resolver = resolverWith(mapOf(230 to crave))

        repeat(50) { resolver.resolve(listOf(RawProviderOffer(230, "Crave", AccessType.SUBSCRIPTION))) }

        // A per-title round trip would be the obvious source of a slow refresh.
        verify(exactly = 1) { repository.loadAliasMap() }
    }

    @Test
    fun `refresh reloads the mapping without a restart`() {
        val resolver = resolverWith(mapOf(230 to crave))
        resolver.resolve(emptyList())

        resolver.refresh()
        resolver.resolve(emptyList())

        verify(exactly = 2) { repository.loadAliasMap() }
    }
}
