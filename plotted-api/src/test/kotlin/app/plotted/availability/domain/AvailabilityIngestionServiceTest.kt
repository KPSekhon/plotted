package app.plotted.availability.domain

import app.plotted.availability.integration.tmdb.TmdbOfferMapper
import app.plotted.availability.persistence.AvailabilityRepository
import app.plotted.platform.integration.tmdb.TmdbClient
import app.plotted.platform.integration.tmdb.TmdbException
import app.plotted.platform.integration.tmdb.TmdbMediaType
import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.integration.tmdb.TmdbProvider
import app.plotted.platform.integration.tmdb.TmdbRegionProviders
import app.plotted.platform.integration.tmdb.TmdbWatchProviderResponse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AvailabilityIngestionServiceTest {
    private val crave = Provider(UUID.randomUUID(), "Crave", "crave", ProviderType.SUBSCRIPTION)
    private val netflix = Provider(UUID.randomUUID(), "Netflix", "netflix", ProviderType.SUBSCRIPTION)
    private val appleStore = Provider(UUID.randomUUID(), "Apple TV (Store)", "apple-tv-store", ProviderType.TRANSACTIONAL)

    private val titleId = UUID.randomUUID()

    private val client = mockk<TmdbClient>()
    private val repository = mockk<AvailabilityRepository>(relaxed = true)
    private val providers = mockk<app.plotted.availability.persistence.ProviderRepository>()
    private val changes = mockk<app.plotted.availability.persistence.AvailabilityChangeRepository>(relaxed = true)
    private val outbox = mockk<app.plotted.platform.persistence.OutboxRepository>(relaxed = true)

    private fun serviceWith(aliases: Map<Int, Provider>): AvailabilityIngestionService {
        every { providers.loadAliasMap() } returns aliases
        return AvailabilityIngestionService(
            client = client,
            offerMapper = TmdbOfferMapper(),
            resolver = ProviderResolver(providers),
            availability = repository,
            changes = changes,
            outbox = outbox,
            properties = TmdbProperties(readAccessToken = "test"),
        )
    }

    // --- the diff, which is pure ------------------------------------------

    @Test
    fun `a newly listed provider is an addition`() {
        val service = serviceWith(emptyMap())

        val diff = service.diff(stored = emptyList(), desired = listOf(offer(crave, AccessType.SUBSCRIPTION)))

        diff.added.size shouldBe 1
        diff.removed.shouldContainExactly(emptyList())
        diff.hasChanges shouldBe true
    }

    @Test
    fun `a provider that has dropped the title is a removal`() {
        val service = serviceWith(emptyMap())
        val existing = storedRow(crave, AccessType.SUBSCRIPTION)

        val diff = service.diff(stored = listOf(existing), desired = emptyList())

        diff.removed.shouldContainExactly(listOf(existing))
        diff.added.shouldContainExactly(emptyList())
    }

    @Test
    fun `an unchanged listing is neither added nor removed`() {
        val service = serviceWith(emptyMap())
        val existing = storedRow(crave, AccessType.SUBSCRIPTION)

        val diff = service.diff(listOf(existing), listOf(offer(crave, AccessType.SUBSCRIPTION)))

        diff.hasChanges shouldBe false
        diff.unchanged.shouldContainExactly(listOf(existing))
    }

    @Test
    fun `the same provider changing access type is a removal and an addition`() {
        val service = serviceWith(emptyMap())
        // Included with a subscription yesterday, rental only today. This is
        // exactly the change Plot Armour exists to notice.
        val existing = storedRow(crave, AccessType.SUBSCRIPTION)

        val diff = service.diff(listOf(existing), listOf(offer(crave, AccessType.RENT)))

        diff.removed.shouldContainExactly(listOf(existing))
        diff.added.single().accessType shouldBe AccessType.RENT
    }

    @Test
    fun `a price change alone is not a change of offer`() {
        val service = serviceWith(emptyMap())
        val existing = storedRow(appleStore, AccessType.RENT, price = BigDecimal("4.99"))

        val diff = service.diff(listOf(existing), listOf(offer(appleStore, AccessType.RENT)))

        // A rental going from $4.99 to $5.99 is the same offer at a new price.
        // Treating it as removal-plus-addition would fill the change history
        // with noise the removal-risk model would then try to learn from.
        diff.hasChanges shouldBe false
    }

    // --- the hash ----------------------------------------------------------

    @Test
    fun `the hash ignores the order upstream happened to return providers in`() {
        val service = serviceWith(emptyMap())

        val one = service.hashOf(listOf(offer(crave, AccessType.SUBSCRIPTION), offer(netflix, AccessType.SUBSCRIPTION)))
        val other = service.hashOf(listOf(offer(netflix, AccessType.SUBSCRIPTION), offer(crave, AccessType.SUBSCRIPTION)))

        one shouldBe other
    }

    @Test
    fun `the hash changes when the offer set really changes`() {
        val service = serviceWith(emptyMap())

        val before = service.hashOf(listOf(offer(crave, AccessType.SUBSCRIPTION)))
        val after = service.hashOf(listOf(offer(crave, AccessType.RENT)))

        before shouldNotBe after
    }

    @Test
    fun `an empty offer set still hashes, so nowhere-available is recordable`() {
        val service = serviceWith(emptyMap())

        service.hashOf(emptyList()).length shouldBe 64
    }

    // --- the refresh -------------------------------------------------------

    @Test
    fun `a refresh opens new rows, closes gone ones and re-verifies the rest`() {
        val service = serviceWith(mapOf(230 to crave, 8 to netflix))
        val goneRow = storedRow(netflix, AccessType.SUBSCRIPTION)
        every { repository.findActive(titleId, "CA") } returns listOf(goneRow)
        every { client.watchProviders(any(), any()) } returns providerResponse(230 to "Crave")

        val outcome = service.refresh(titleId, TmdbMediaType.MOVIE, 1)

        outcome as AvailabilityIngestionService.RefreshOutcome.Refreshed
        verify { repository.open(titleId, crave.id, "CA", AccessType.SUBSCRIPTION, any(), any(), any(), any(), any()) }
        verify { repository.close(goneRow.id) }
        verify { repository.markVerified(emptyList()) }
    }

    @Test
    fun `a snapshot is written even when nothing changed`() {
        val service = serviceWith(mapOf(230 to crave))
        every { repository.findActive(titleId, "CA") } returns listOf(storedRow(crave, AccessType.SUBSCRIPTION))
        every { client.watchProviders(any(), any()) } returns providerResponse(230 to "Crave")

        service.refresh(titleId, TmdbMediaType.MOVIE, 1)

        // A day with no change is still a day of evidence that the title was
        // still there, and the risk model needs both.
        verify { repository.recordSnapshot(titleId, "CA", any(), any()) }
    }

    @Test
    fun `an upstream outage writes nothing at all`() {
        val service = serviceWith(mapOf(230 to crave))
        every { client.watchProviders(any(), any()) } throws TmdbException.Upstream(503, "down")

        val outcome = service.refresh(titleId, TmdbMediaType.MOVIE, 1)

        (outcome is AvailabilityIngestionService.RefreshOutcome.Unavailable) shouldBe true
        // Closing rows because a request failed would tell a user a title had
        // left a service it is still on, and would record a removal in the
        // snapshot history that never happened.
        verify(exactly = 0) { repository.close(any()) }
        verify(exactly = 0) { repository.open(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { repository.recordSnapshot(any(), any(), any(), any()) }
    }

    @Test
    fun `a title with no provider record closes what was stored`() {
        val service = serviceWith(mapOf(230 to crave))
        val existing = storedRow(crave, AccessType.SUBSCRIPTION)
        every { repository.findActive(titleId, "CA") } returns listOf(existing)
        // Distinct from an outage: TMDB answered, and the answer is "nobody".
        every { client.watchProviders(any(), any()) } throws TmdbException.NotFound("/movie/1/watch/providers")

        service.refresh(titleId, TmdbMediaType.MOVIE, 1)

        verify { repository.close(existing.id) }
        verify { repository.recordSnapshot(titleId, "CA", any(), any()) }
    }

    @Test
    fun `confidence is reduced when part of the response could not be mapped`() {
        val service = serviceWith(mapOf(230 to crave))
        every { repository.findActive(titleId, "CA") } returns emptyList()
        every { client.watchProviders(any(), any()) } returns
            providerResponse(230 to "Crave", 99999 to "Some New Service")

        val confidence = slot<BigDecimal>()
        every {
            repository.open(any(), any(), any(), any(), any(), capture(confidence), any(), any(), any())
        } returns UUID.randomUUID()

        service.refresh(titleId, TmdbMediaType.MOVIE, 1)

        // A mapping gap means part of the picture is missing, so nothing seen
        // on that pass is fully trusted.
        (confidence.captured < BigDecimal.ONE) shouldBe true
    }

    @Test
    fun `only the configured region is read`() {
        val service = serviceWith(mapOf(8 to netflix))
        every { repository.findActive(titleId, "CA") } returns emptyList()
        every { client.watchProviders(any(), any()) } returns
            TmdbWatchProviderResponse(
                results = mapOf(
                    "US" to TmdbRegionProviders(flatrate = listOf(TmdbProvider(8, "Netflix"))),
                ),
            )

        val outcome = service.refresh(titleId, TmdbMediaType.MOVIE, 1)

        outcome as AvailabilityIngestionService.RefreshOutcome.Refreshed
        outcome.diff.added.shouldContainExactly(emptyList())
        verify(exactly = 0) { repository.open(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // --- helpers -----------------------------------------------------------

    private fun offer(provider: Provider, accessType: AccessType) =
        ProviderOffer(provider, accessType, sourceTmdbProviderId = 1, sourceName = provider.name)

    private fun storedRow(provider: Provider, accessType: AccessType, price: BigDecimal? = null) = StoredAvailability(
        id = UUID.randomUUID(),
        providerId = provider.id,
        accessType = accessType,
        price = price,
        currency = price?.let { "CAD" },
        deepLink = null,
        sourceCheckedAt = Instant.parse("2026-07-26T00:00:00Z"),
        confidence = BigDecimal("1.000"),
    )

    private fun providerResponse(vararg flatrate: Pair<Int, String>) = TmdbWatchProviderResponse(
        results = mapOf(
            "CA" to TmdbRegionProviders(
                flatrate = flatrate.map { (id, name) -> TmdbProvider(id, name) },
            ),
        ),
    )
}
