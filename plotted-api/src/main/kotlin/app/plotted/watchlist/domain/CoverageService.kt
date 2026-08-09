package app.plotted.watchlist.domain

import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.watchlist.persistence.WatchlistRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Which service covers the most of what someone actually wants to watch.
 *
 * This is the first screen in Plotted that answers a question rather than
 * displaying a record, and it is the direct ancestor of the phase 5 optimiser --
 * the same weighted coverage number, computed for one service at a time instead
 * of searched over combinations. Getting the definition right here means phase 5
 * inherits it rather than inventing a second one that disagrees.
 *
 * ### What is counted
 *
 * Only *outstanding* items. Something already watched tells you nothing about
 * which service to pay for next month, and counting completed items would turn
 * this into a measure of history rather than of intent.
 *
 * Coverage is **priority-weighted**, not a count. A service carrying the one
 * film someone is desperate to see should outrank a service carrying four they
 * are lukewarm about, and an unweighted percentage cannot express that -- it
 * would report the two as 20% and 80% and be confidently backwards about which
 * subscription to keep.
 *
 * ### What is deliberately not counted
 *
 * Titles nobody has checked availability for are excluded from the denominator
 * and reported separately. Scoring them as uncovered would make every service
 * look worse in exact proportion to how stale the catalogue is, which is a
 * property of Plotted's data pipeline rather than of the service -- and it would
 * do so invisibly, since a low percentage looks the same either way.
 */
@Service
class CoverageService(
    private val watchlists: WatchlistRepository,
    private val availability: AvailabilityDirectory,
    private val titles: TitleDirectory,
) {
    @Transactional(readOnly = true)
    fun forUser(userId: UUID, regionCode: String): CoverageReport {
        // Reads the list; never provisions one. This method is `readOnly`, so an
        // insert here is not caught by a check -- Postgres refuses it outright
        // and the dashboard answers 500. It stayed hidden because the failure
        // heals itself: by the second request some read-write endpoint has
        // created the row, and the screen works forever after.
        val outstanding = watchlists.findDefault(userId)
            ?.let { list -> watchlists.findItems(list.id).filter { it.status.isOutstanding } }
            .orEmpty()

        if (outstanding.isEmpty()) {
            return CoverageReport(
                regionCode = regionCode,
                consideredTitles = 0,
                unknownTitles = 0,
                totalWeight = 0.0,
                providers = emptyList(),
            )
        }

        val coverage = availability.subscriptionCoverage(outstanding.map { it.titleId }, regionCode)
        val summaries = titles.findSummaries(outstanding.map { it.titleId }).associateBy { it.titleId }

        // The denominator: items we actually know something about. Everything
        // downstream is a share of this, so what goes in it is the single most
        // consequential decision in this class.
        val considered = outstanding.filterNot { coverage.unknownTitleIds.contains(it.titleId) }
        val totalWeight = considered.sumOf { it.priority.weight }

        val providers = considered
            .flatMap { item ->
                coverage.byTitle[item.titleId].orEmpty().map { provider -> provider to item }
            }
            .groupBy({ it.first.providerId }, { it })
            .map { (_, pairs) ->
                val provider = pairs.first().first
                val items = pairs.map { it.second }
                val weight = items.sumOf { it.priority.weight }
                ProviderCoverage(
                    providerId = provider.providerId,
                    name = provider.name,
                    slug = provider.slug,
                    logoUrl = provider.logoUrl,
                    titleCount = items.size,
                    weightedShare = if (totalWeight == 0.0) 0.0 else weight / totalWeight,
                    // The titles themselves, so the interface can show its work.
                    // A percentage nobody can drill into is a number the user has
                    // to take on trust, and this one is asking them to spend money.
                    titles = items
                        .sortedWith(compareBy({ it.priority.value }, { summaries[it.titleId]?.name ?: "" }))
                        .map { item ->
                            CoveredTitle(
                                titleId = item.titleId,
                                name = summaries[item.titleId]?.name,
                                priority = item.priority.value,
                            )
                        },
                )
            }
            .sortedWith(compareByDescending<ProviderCoverage> { it.weightedShare }.thenBy { it.name })

        return CoverageReport(
            regionCode = regionCode,
            consideredTitles = considered.size,
            unknownTitles = coverage.unknownTitleIds.size,
            totalWeight = totalWeight,
            providers = providers,
        )
    }

    data class CoverageReport(
        val regionCode: String,
        /** Outstanding items with availability data — the denominator. */
        val consideredTitles: Int,
        /** Outstanding items nobody has checked yet. Reported, never scored. */
        val unknownTitles: Int,
        val totalWeight: Double,
        val providers: List<ProviderCoverage>,
    )

    data class ProviderCoverage(
        val providerId: UUID,
        val name: String,
        val slug: String,
        val logoUrl: String?,
        val titleCount: Int,
        /** Share of the total priority weight this one service covers, 0..1. */
        val weightedShare: Double,
        val titles: List<CoveredTitle>,
    )

    data class CoveredTitle(
        val titleId: UUID,
        val name: String?,
        val priority: Int,
    )
}
