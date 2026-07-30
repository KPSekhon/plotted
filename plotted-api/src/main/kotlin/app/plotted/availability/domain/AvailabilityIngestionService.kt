package app.plotted.availability.domain

import app.plotted.availability.integration.tmdb.TmdbOfferMapper
import app.plotted.availability.persistence.AvailabilityRepository
import app.plotted.platform.events.TitleIngested
import app.plotted.platform.integration.tmdb.TmdbClient
import app.plotted.platform.integration.tmdb.TmdbException
import app.plotted.platform.integration.tmdb.TmdbMediaType
import app.plotted.platform.integration.tmdb.TmdbProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

/**
 * Refreshes where a title can be watched, and records what was seen.
 *
 * Two outputs, and the second matters more in the long run:
 *
 *  * `title_availability` is updated to match what upstream reports now, by
 *    opening and closing dated windows rather than overwriting rows.
 *  * `availability_snapshots` gains a row on every refresh, changed or not.
 *
 * The snapshot history is the one asset in this project that cannot be
 * re-downloaded later. Plot Armour's removal-risk model needs months of it
 * before it can exist at all, which is why collection starts in phase 2 and the
 * model is a phase 12 item. A day with no change is still a day of evidence that
 * the title was still there.
 */
@Service
class AvailabilityIngestionService(
    private val client: TmdbClient,
    private val offerMapper: TmdbOfferMapper,
    private val resolver: ProviderResolver,
    private val availability: AvailabilityRepository,
    private val properties: TmdbProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Runs after the catalogue transaction commits, in its own.
     *
     * `AFTER_COMMIT` rather than a direct call: a title that stored perfectly
     * well must not be rolled back because TMDB's provider endpoint was having a
     * bad minute. The two concerns fail independently, so they commit
     * independently.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onTitleIngested(event: TitleIngested) {
        val mediaType = if (event.mediaType == "movie") TmdbMediaType.MOVIE else TmdbMediaType.TV
        val tmdbId = event.externalId.toIntOrNull()
        if (tmdbId == null) {
            log.warn("Title {} has a non-numeric TMDB id '{}'; skipping availability", event.titleId, event.externalId)
            return
        }
        refresh(event.titleId, mediaType, tmdbId)
    }

    @Transactional
    fun refresh(titleId: UUID, mediaType: TmdbMediaType, tmdbId: Int): RefreshOutcome {
        val region = properties.region

        val raw =
            try {
                val response = client.watchProviders(mediaType, tmdbId)
                offerMapper.toRawOffers(response.results[region])
            } catch (failure: TmdbException.NotFound) {
                // The title exists but has no provider record. Distinct from an
                // outage, and it means nobody carries it -- so anything stored
                // should be closed rather than left looking current.
                emptyList()
            } catch (failure: TmdbException) {
                // Nothing is written on an outage, deliberately. Closing rows
                // because a request failed would show a user that a title had
                // "left" a service it is still on, and the snapshot history
                // would record a removal that never happened.
                log.warn("Availability refresh for title {} failed: {}", titleId, failure.message)
                return RefreshOutcome.Unavailable(titleId, failure.message ?: "unknown")
            }

        val resolution = resolver.resolve(raw)
        val stored = availability.findActive(titleId, region)
        val diff = diff(stored, resolution.offers)

        diff.added.forEach { offer ->
            availability.open(
                titleId = titleId,
                providerId = offer.provider.id,
                regionCode = region,
                accessType = offer.accessType,
                source = SOURCE,
                // Section 7.3: this feed is imperfect for smaller Canadian
                // services. A gap in the mapping means part of the picture is
                // missing, so nothing seen on that pass is fully trusted.
                confidence = if (resolution.hasGaps) REDUCED_CONFIDENCE else FULL_CONFIDENCE,
            )
        }
        diff.removed.forEach { availability.close(it.id) }
        availability.markVerified(diff.unchanged.map { it.id })

        val hash = hashOf(resolution.offers)
        availability.recordSnapshot(
            titleId = titleId,
            regionCode = region,
            availabilityHash = hash,
            rawSummary = summaryOf(resolution),
        )

        if (diff.hasChanges) {
            log.info(
                "Availability for title {} changed: {} added, {} removed, {} unchanged",
                titleId,
                diff.added.size,
                diff.removed.size,
                diff.unchanged.size,
            )
        }

        return RefreshOutcome.Refreshed(titleId, diff, resolution.unmapped.size, hash)
    }

    /**
     * Matches stored rows against what upstream reports, on provider and access
     * type.
     *
     * Price is deliberately not part of the identity. A rental going from $4.99
     * to $5.99 is the same offer at a new price, not the removal of one offer and
     * the arrival of another -- and treating it as the latter would fill the
     * change history with noise that Plot Armour would then try to learn from.
     */
    internal fun diff(stored: List<StoredAvailability>, desired: List<ProviderOffer>): AvailabilityDiff {
        val desiredKeys = desired.map { it.provider.id to it.accessType }.toSet()
        val storedKeys = stored.map { it.providerId to it.accessType }.toSet()

        return AvailabilityDiff(
            added = desired.filterNot { (it.provider.id to it.accessType) in storedKeys },
            removed = stored.filterNot { (it.providerId to it.accessType) in desiredKeys },
            unchanged = stored.filter { (it.providerId to it.accessType) in desiredKeys },
        )
    }

    /**
     * A stable digest of the canonical offer set.
     *
     * Sorted before hashing so that upstream reordering the same providers does
     * not read as a change. This is what makes "did anything actually move
     * today?" a single comparison over months of history.
     */
    internal fun hashOf(offers: List<ProviderOffer>): String {
        val canonical = offers
            .map { "${it.provider.slug}:${it.accessType.dbValue}" }
            .sorted()
            .joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * What was seen, in a form that survives the mapping being wrong.
     *
     * Unmapped providers are recorded here even though they produce no
     * availability row. If the alias seed gains an entry six months from now,
     * this is what lets the history be re-read rather than being permanently
     * missing that service.
     */
    private fun summaryOf(resolution: ProviderResolution): Map<String, Any?> = mapOf(
        "offers" to resolution.offers.map {
            mapOf(
                "provider" to it.provider.slug,
                "accessType" to it.accessType.dbValue,
                "sourceTmdbProviderId" to it.sourceTmdbProviderId,
                "sourceName" to it.sourceName,
            )
        },
        "unmapped" to resolution.unmapped.map {
            mapOf(
                "tmdbProviderId" to it.tmdbProviderId,
                "name" to it.providerName,
                "accessType" to it.accessType.dbValue,
            )
        },
    )

    sealed interface RefreshOutcome {
        data class Refreshed(
            val titleId: UUID,
            val diff: AvailabilityDiff,
            val unmappedCount: Int,
            val availabilityHash: String,
        ) : RefreshOutcome

        /** Upstream could not be reached. Nothing was written. */
        data class Unavailable(
            val titleId: UUID,
            val reason: String,
        ) : RefreshOutcome
    }

    private companion object {
        const val SOURCE = "tmdb:justwatch"
        val FULL_CONFIDENCE: BigDecimal = BigDecimal("1.000")

        /** Applied when part of the upstream response could not be mapped. */
        val REDUCED_CONFIDENCE: BigDecimal = BigDecimal("0.800")
    }
}
