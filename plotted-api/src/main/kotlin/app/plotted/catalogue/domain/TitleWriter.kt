package app.plotted.catalogue.domain

import app.plotted.catalogue.persistence.TitleRepository
import app.plotted.platform.events.TitleIngested
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Stores an ingested title and announces it, in one transaction.
 *
 * ### Why this is a separate bean rather than a method on the service
 *
 * `TitleRepository.upsert` is already `@Transactional`, so the title, its
 * type-specific row and its genre links were always written atomically. The bug
 * was one level up: that transaction **commits when `upsert` returns**, and the
 * `TitleIngested` event was published afterwards, outside any transaction at
 * all. `AvailabilityIngestionService` listens with `@TransactionalEventListener`,
 * which discards events published outside a transaction — so every ingest stored
 * a title and dropped its availability fetch, silently, for the life of the
 * project. 503 titles, zero availability rows, no error anywhere.
 *
 * The fix has to put the write and the publish inside *one* transaction, so the
 * commit the listener waits for is the commit that stored the title. That cannot
 * be an annotation on `TitleIngestionService.ingest`, for two reasons:
 *
 *  * `ingestWithSeasons` and `ingestAll` both call `ingest` on themselves, and
 *    Spring's `@Transactional` works through a proxy — a self-call gets no
 *    transaction, so series and batches would have carried on failing while
 *    single ingests started working. That is a worse state than the bug, because
 *    it looks fixed.
 *  * `ingest` fetches from TMDB before it writes. Wrapping the whole method
 *    would hold a database connection open across the HTTP call, and across a
 *    dozen of them for a multi-season series.
 *
 * So the transaction lives here, on the far side of a proxy boundary, and covers
 * only the persistence and the announcement. The network I/O stays outside it.
 */
@Component
class TitleWriter(
    private val titles: TitleRepository,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Writes the title and publishes [TitleIngested] within the same transaction.
     *
     * `upsert` is itself `@Transactional` and joins this one rather than opening
     * its own, so there is a single commit and the listener fires against it.
     */
    @Transactional
    fun store(ingested: IngestedTitle, mediaType: MediaType): TitleRepository.UpsertResult {
        val result = titles.upsert(ingested)

        if (result.unknownGenreIds.isNotEmpty()) {
            log.warn(
                "Title {} referenced unseeded genre ids {}; the genre seed needs updating",
                ingested.name,
                result.unknownGenreIds,
            )
        }

        // Inside the transaction on purpose. Published outside one, this is
        // dropped rather than delivered, which is the whole bug this class
        // exists to fix. The availability refresh still runs in its own
        // transaction afterwards -- a TMDB provider outage must not roll back a
        // title that stored perfectly well.
        events.publishEvent(
            TitleIngested(
                titleId = result.titleId,
                externalId = ingested.externalId,
                mediaType = mediaType.dbValue,
                created = result.created,
            ),
        )

        return result
    }
}
