package app.plotted.catalogue.domain

import app.plotted.platform.integration.tmdb.TmdbProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * Loads the curated Canadian seed set.
 *
 * The specification asks for the seed to be "a versioned SQL fixture". It is a
 * list of titles and a runner instead, for a reason worth stating: writing the
 * fixture as SQL would mean hand-writing runtimes, ratings and provider rows,
 * which is inventing the data the product is supposed to source. Resolving each
 * title through the real ingestion path produces real metadata and real
 * availability, and exercises the pipeline while doing it. What is versioned is
 * the curation -- the list of titles a person chose -- which is the part that
 * actually carries judgement.
 *
 * Idempotent, because ingestion is: running it twice refreshes rather than
 * duplicates, so it doubles as a way to re-pull the whole seed after a schema
 * change.
 *
 *     ./gradlew :plotted-api:bootRun --args='--plotted.catalogue.seed.enabled=true'
 */
@Component
@ConditionalOnProperty(prefix = "plotted.catalogue.seed", name = ["enabled"], havingValue = "true")
class CatalogueSeedRunner(
    private val ingestion: TitleIngestionService,
    private val tmdb: TmdbProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!tmdb.isConfigured) {
            log.error("Cannot seed the catalogue: TMDB is not configured. Set TMDB_READ_ACCESS_TOKEN.")
            return
        }

        val titles = readSeedList()
        if (titles.isEmpty()) {
            log.warn("Seed list at {} is empty", SEED_PATH)
            return
        }

        log.info("Seeding {} titles for region {}", titles.size, tmdb.region)
        val report = Report()

        titles.forEach { entry ->
            val name = entry.label

            val resolved = when (entry) {
                // Derived from the Watchmode enumeration, which carries the tmdb
                // id itself. No search, so nothing to resolve wrongly -- and one
                // fewer request per title, which over four hundred of them is the
                // difference between a seed run and a seed afternoon.
                is SeedEntry.ByTmdbId -> entry.tmdbId to entry.mediaType

                // Curated by hand, so it is a name and has to be looked up. The
                // same path a user adding to a watchlist takes, which is what
                // makes a seed run proof that the real pipeline works.
                is SeedEntry.ByName -> {
                    val match = ingestion.search(entry.name).firstOrNull()
                    val tmdbId = match?.externalId?.toIntOrNull()
                    if (match == null || tmdbId == null) {
                        log.warn("No TMDB match for '{}'", entry.name)
                        report.unmatched += entry.name
                        return@forEach
                    }
                    tmdbId to match.mediaType
                }

                is SeedEntry.Malformed -> {
                    log.warn("Malformed seed line, skipped: '{}'", entry.line)
                    report.failed += "${entry.line}: not a valid tmdb:<id>:<movie|tv> line"
                    return@forEach
                }
            }
            val (tmdbId, mediaType) = resolved

            // Series go through the season path so the seeded catalogue carries
            // measured runtimes rather than episode-count times an average.
            // Tonight Mode's time filter is a hard filter, and a seed built on
            // estimates would make its promise only as good as a guess. It costs
            // one extra request per season, which is what the seed is for.
            val outcome =
                if (mediaType == MediaType.SERIES) {
                    ingestion.ingestWithSeasons(tmdbId)
                } else {
                    ingestion.ingest(mediaType, tmdbId)
                }

            when (outcome) {
                is TitleIngestionService.IngestionOutcome.Ingested -> {
                    if (outcome.created) report.created++ else report.refreshed++
                    if (outcome.metadataStatus != MetadataStatus.COMPLETE) {
                        // Usually a missing runtime, which matters: a title
                        // without one cannot be recommended into a time window.
                        report.incomplete += "${outcome.name} (${outcome.metadataStatus.dbValue})"
                    }
                    // Worth seeing when the search resolved to something else.
                    if (!outcome.name.equals(name, ignoreCase = true)) {
                        log.info("'{}' resolved to '{}'", name, outcome.name)
                    }
                }

                is TitleIngestionService.IngestionOutcome.NotFound -> report.unmatched += name
                is TitleIngestionService.IngestionOutcome.Failed -> report.failed += "$name: ${outcome.reason}"
            }
        }

        log.info("Seed complete. {}", report.summary(titles.size))
        if (report.unmatched.isNotEmpty()) {
            log.warn("Unmatched titles (check spelling in {}): {}", SEED_PATH, report.unmatched)
        }
        if (report.incomplete.isNotEmpty()) {
            log.warn("Ingested but incomplete, so excluded from timed recommendations: {}", report.incomplete)
        }
        if (report.failed.isNotEmpty()) {
            log.error("Failed: {}", report.failed)
        }
        log.info("Availability is fetched per title after ingestion; check the log above for provider gaps.")
    }

    private fun readSeedList(): List<SeedEntry> = ClassPathResource(SEED_PATH).inputStream.bufferedReader().useLines { lines ->
        lines
            // Trailing comments carry the human-readable title and the providers
            // that were carrying it, which is what makes a file of ids reviewable
            // by eye. Stripped here rather than in the generator so a person can
            // annotate a line without breaking it.
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map(::parseEntry)
            .toList()
    }

    /**
     * A seed line.
     *
     * `tmdb:634649:movie` is derived from the availability enumeration and needs
     * no lookup. Anything else is a title somebody typed, and gets searched.
     * A malformed `tmdb:` line is reported rather than silently searched as a
     * name, because "tmdb:634649:movie" is not a film and the search would
     * return something arbitrary.
     */
    private fun parseEntry(line: String): SeedEntry {
        if (!line.startsWith(TMDB_PREFIX)) return SeedEntry.ByName(line)

        val parts = line.removePrefix(TMDB_PREFIX).split(':')
        val id = parts.getOrNull(0)?.toIntOrNull()
        val mediaType = when (parts.getOrNull(1)) {
            "movie" -> MediaType.MOVIE
            "tv", "series" -> MediaType.SERIES
            else -> null
        }
        return if (id == null || mediaType == null) SeedEntry.Malformed(line) else SeedEntry.ByTmdbId(id, mediaType)
    }

    private sealed interface SeedEntry {
        /** What to call it in a log line, before anything has been resolved. */
        val label: String

        data class ByTmdbId(val tmdbId: Int, val mediaType: MediaType) : SeedEntry {
            override val label: String get() = "tmdb:$tmdbId"
        }

        data class ByName(val name: String) : SeedEntry {
            override val label: String get() = name
        }

        data class Malformed(val line: String) : SeedEntry {
            override val label: String get() = line
        }
    }

    private class Report {
        var created = 0
        var refreshed = 0
        val unmatched = mutableListOf<String>()
        val incomplete = mutableListOf<String>()
        val failed = mutableListOf<String>()

        fun summary(requested: Int): String = "$requested requested: $created new, $refreshed refreshed, " +
            "${unmatched.size} unmatched, ${incomplete.size} incomplete, ${failed.size} failed"
    }

    private companion object {
        const val SEED_PATH = "seed/canadian-seed.txt"
        const val TMDB_PREFIX = "tmdb:"
    }
}
