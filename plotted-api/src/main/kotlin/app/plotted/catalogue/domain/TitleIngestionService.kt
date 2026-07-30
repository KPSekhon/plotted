package app.plotted.catalogue.domain

import app.plotted.catalogue.integration.tmdb.TmdbTitleMapper
import app.plotted.catalogue.persistence.TitleRepository
import app.plotted.platform.events.TitleIngested
import app.plotted.platform.integration.tmdb.TmdbClient
import app.plotted.platform.integration.tmdb.TmdbException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
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
    private val titles: TitleRepository,
    private val events: ApplicationEventPublisher,
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

    fun ingest(mediaType: MediaType, tmdbId: Int): IngestionOutcome = try {
        val ingested =
            when (mediaType) {
                MediaType.MOVIE -> mapper.toIngestedTitle(client.movie(tmdbId))
                MediaType.SERIES -> mapper.toIngestedTitle(client.series(tmdbId))
            }
        val result = titles.upsert(ingested)

        if (result.unknownGenreIds.isNotEmpty()) {
            log.warn(
                "Title {} referenced unseeded genre ids {}; the genre seed needs updating",
                ingested.name,
                result.unknownGenreIds,
            )
        }

        // Published after the write, so a listener that reads the title back
        // will find it. Availability refresh happens on its own transaction:
        // a TMDB provider outage must not roll back a title that stored fine.
        events.publishEvent(
            TitleIngested(
                titleId = result.titleId,
                externalId = ingested.externalId,
                mediaType = mediaType.dbValue,
                created = result.created,
            ),
        )

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
