package app.plotted.catalogue.domain

import app.plotted.catalogue.integration.tmdb.TmdbTitleMapper
import app.plotted.catalogue.persistence.SeasonRepository
import app.plotted.platform.integration.tmdb.TmdbClient
import app.plotted.platform.integration.tmdb.TmdbException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Pulls a title from TMDB and stores it.
 *
 * The failure handling is the substance here. A catalogue refresh walks
 * thousands of titles, and the useful behaviour is to keep going and report what
 * did not work, rather than to abort the run on the first title TMDB has since
 * deleted. So every outcome is a value, not an exception: callers decide what a
 * missing title means, and a batch can summarise its own failures.
 */
@Service
class TitleIngestionService(
    private val client: TmdbClient,
    private val mapper: TmdbTitleMapper,
    private val writer: TitleWriter,
    private val seasons: SeasonRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Searches TMDB directly rather than the local catalogue.
     *
     * Deliberate: a user adding to a watchlist wants everything that exists, not
     * only what Plotted happens to have ingested already. Local search over the
     * stored catalogue is a different feature with a different index.
     */
    fun search(query: String): List<TitleSearchResult> {
        if (query.isBlank()) return emptyList()
        return runCatching { mapper.toSearchResults(client.searchMulti(query.trim())) }
            .getOrElse { failure ->
                log.warn("TMDB search for '{}' failed: {}", query, failure.message)
                emptyList()
            }
    }

    /**
     * Ingests a series and then its seasons, replacing the estimated total
     * runtime with one summed from real episodes.
     *
     * Separate from [ingest] because it is expensive: one request per season on
     * top of the series itself. Tonight Mode's time filter is a hard filter, so
     * "it fits" needs to rest on measured runtimes rather than an average — but
     * that is worth paying for deliberately, not by accident on every ingest.
     */
    fun ingestWithSeasons(tmdbId: Int): IngestionOutcome {
        val outcome = ingest(MediaType.SERIES, tmdbId)
        if (outcome !is IngestionOutcome.Ingested) return outcome

        val seasonNumbers = runCatching { client.series(tmdbId) }
            .getOrNull()
            ?.seasons
            ?.map { it.seasonNumber }
            ?: return outcome

        var stored = 0
        seasonNumbers.forEach { number ->
            // One bad season must not cost the whole series. A show with a
            // partially ingested episode list is still more useful than one
            // stuck on an average.
            runCatching { mapper.toIngestedSeason(client.season(tmdbId, number)) }
                .onSuccess {
                    seasons.upsert(outcome.titleId, it)
                    stored++
                }
                .onFailure { log.warn("Season {} of TMDB series {} failed: {}", number, tmdbId, it.message) }
        }

        if (stored > 0) {
            val total = seasons.recalculateTotalRuntime(outcome.titleId)
            log.info(
                "Ingested {} seasons of '{}'; total runtime now {}",
                stored,
                outcome.name,
                total?.let { "$it min (measured)" } ?: "unchanged (no episode runtimes upstream)",
            )
        }
        return outcome
    }

    fun ingest(mediaType: MediaType, tmdbId: Int): IngestionOutcome = try {
        // The network call stays outside the transaction. Wrapping this method
        // instead would hold a database connection across it -- and across one
        // per season for a series.
        val ingested =
            when (mediaType) {
                MediaType.MOVIE -> mapper.toIngestedTitle(client.movie(tmdbId))
                MediaType.SERIES -> mapper.toIngestedTitle(client.series(tmdbId))
            }

        // Writes and announces in one transaction. Deliberately a separate bean:
        // this method is called on `this` by `ingestWithSeasons` and `ingestAll`,
        // and a `@Transactional` annotation here would not apply to either --
        // Spring's proxy is only crossed on a call from outside. See
        // [TitleWriter] for what that cost the availability pipeline.
        val result = writer.store(ingested, mediaType)

        IngestionOutcome.Ingested(result.titleId, ingested.name, result.created, ingested.metadataStatus)
    } catch (failure: TmdbException.NotFound) {
        // The title genuinely no longer exists upstream. Not an error worth
        // failing a batch over, but worth recording.
        log.info("TMDB has no {} with id {}", mediaType.dbValue, tmdbId)
        IngestionOutcome.NotFound(tmdbId)
    } catch (failure: TmdbException) {
        log.warn("Ingesting {} {} failed: {}", mediaType.dbValue, tmdbId, failure.message)
        IngestionOutcome.Failed(tmdbId, failure.message ?: failure::class.simpleName.orEmpty(), failure.retryable)
    }

    /**
     * Ingests many titles, reporting per-title outcomes rather than stopping at
     * the first failure.
     */
    fun ingestAll(requests: List<Request>): BatchReport {
        val outcomes = requests.map { ingest(it.mediaType, it.tmdbId) }
        return BatchReport(
            ingested = outcomes.filterIsInstance<IngestionOutcome.Ingested>(),
            notFound = outcomes.filterIsInstance<IngestionOutcome.NotFound>(),
            failed = outcomes.filterIsInstance<IngestionOutcome.Failed>(),
        )
    }

    data class Request(
        val mediaType: MediaType,
        val tmdbId: Int,
    )

    sealed interface IngestionOutcome {
        data class Ingested(
            val titleId: UUID,
            val name: String,
            val created: Boolean,
            val metadataStatus: MetadataStatus,
        ) : IngestionOutcome

        data class NotFound(
            val tmdbId: Int,
        ) : IngestionOutcome

        data class Failed(
            val tmdbId: Int,
            val reason: String,
            val retryable: Boolean,
        ) : IngestionOutcome
    }

    data class BatchReport(
        val ingested: List<IngestionOutcome.Ingested>,
        val notFound: List<IngestionOutcome.NotFound>,
        val failed: List<IngestionOutcome.Failed>,
    ) {
        val total: Int get() = ingested.size + notFound.size + failed.size
        val createdCount: Int get() = ingested.count { it.created }
        val updatedCount: Int get() = ingested.count { !it.created }

        /** Worth retrying later; a 404 is not. */
        val retryable: List<IngestionOutcome.Failed> get() = failed.filter { it.retryable }

        fun summary(): String = "$total requested: ${ingested.size} stored ($createdCount new, $updatedCount refreshed), " +
            "${notFound.size} missing upstream, ${failed.size} failed (${retryable.size} retryable)"
    }
}
