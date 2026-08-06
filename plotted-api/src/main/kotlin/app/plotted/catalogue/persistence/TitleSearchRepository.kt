package app.plotted.catalogue.persistence

import app.plotted.catalogue.domain.CatalogueTitle
import app.plotted.catalogue.domain.MediaType
import app.plotted.catalogue.domain.MetadataStatus
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Search over the titles Plotted has already ingested.
 *
 * Written as plain SQL because both indexes it relies on are fenced out of the
 * jOOQ generator: `search_vector` is a generated `TSVECTOR` column and the
 * trigram index is a GIN index with `gin_trgm_ops` (see V3 and ADR 0004).
 *
 * Two matchers rather than one, because they fail in different places.
 * Full-text handles word matching and stemming -- "dune part" finds
 * "Dune: Part Two" -- but is useless against a typo. Trigram similarity handles
 * "sevrance" but scores poorly on multi-word queries. Ranking takes the better
 * of the two, then breaks ties on popularity, which is what makes the obvious
 * result come first when someone types "the office".
 *
 * OpenSearch was considered and rejected for a catalogue of this size
 * (ADR 0005). This is the query that has to become slow before that changes.
 */
@Repository
class TitleSearchRepository(
    private val dsl: DSLContext,
) {
    fun search(query: String, limit: Int = DEFAULT_LIMIT, mediaType: MediaType? = null): List<CatalogueTitle> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val mediaTypeFilter =
            if (mediaType == null) "" else "AND t.media_type = '${mediaType.dbValue}'"

        // Bound the query text: a multi-kilobyte string produces a tsquery that
        // costs far more to plan than it could ever be worth.
        val safe = trimmed.take(MAX_QUERY_LENGTH)

        return dsl.fetch(
            """
            SELECT t.id,
                   t.media_type,
                   t.name,
                   t.original_name,
                   t.overview,
                   t.release_date,
                   t.poster_url,
                   t.popularity_score,
                   t.community_rating,
                   t.metadata_status,
                   m.runtime_minutes,
                   s.total_runtime_minutes,
                   s.episode_count,
                   GREATEST(
                       ts_rank(t.search_vector, plainto_tsquery('simple', ?)),
                       similarity(t.name, ?)
                   ) AS relevance
              FROM titles t
              LEFT JOIN movies m ON m.title_id = t.id
              LEFT JOIN series s ON s.title_id = t.id
             WHERE (t.search_vector @@ plainto_tsquery('simple', ?) OR t.name % ?)
               $mediaTypeFilter
             ORDER BY relevance DESC, t.popularity_score DESC NULLS LAST, t.name ASC
             LIMIT ?
            """.trimIndent(),
            safe,
            safe,
            safe,
            safe,
            limit.coerceIn(1, MAX_LIMIT),
        ).map { record ->
            CatalogueTitle(
                id = record.get("id", UUID::class.java),
                mediaType = MediaType.fromDb(record.get("media_type", String::class.java)),
                name = record.get("name", String::class.java),
                originalName = record.get("original_name", String::class.java),
                overview = record.get("overview", String::class.java),
                releaseDate = record.get("release_date", LocalDate::class.java),
                posterUrl = record.get("poster_url", String::class.java),
                popularityScore = record.get("popularity_score", BigDecimal::class.java),
                communityRating = record.get("community_rating", BigDecimal::class.java),
                metadataStatus = MetadataStatus.fromDb(record.get("metadata_status", String::class.java)),
                runtimeMinutes = record.get("runtime_minutes", Int::class.javaObjectType),
                totalRuntimeMinutes = record.get("total_runtime_minutes", Int::class.javaObjectType),
                episodeCount = record.get("episode_count", Int::class.javaObjectType),
            )
        }
    }

    fun findById(titleId: UUID): CatalogueTitle? = dsl.fetch(
        """
            SELECT t.id, t.media_type, t.name, t.original_name, t.overview, t.release_date,
                   t.poster_url, t.popularity_score, t.community_rating, t.metadata_status,
                   m.runtime_minutes, s.total_runtime_minutes, s.episode_count
              FROM titles t
              LEFT JOIN movies m ON m.title_id = t.id
              LEFT JOIN series s ON s.title_id = t.id
             WHERE t.id = ?
        """.trimIndent(),
        titleId,
    ).map { record ->
        CatalogueTitle(
            id = record.get("id", UUID::class.java),
            mediaType = MediaType.fromDb(record.get("media_type", String::class.java)),
            name = record.get("name", String::class.java),
            originalName = record.get("original_name", String::class.java),
            overview = record.get("overview", String::class.java),
            releaseDate = record.get("release_date", LocalDate::class.java),
            posterUrl = record.get("poster_url", String::class.java),
            popularityScore = record.get("popularity_score", BigDecimal::class.java),
            communityRating = record.get("community_rating", BigDecimal::class.java),
            metadataStatus = MetadataStatus.fromDb(record.get("metadata_status", String::class.java)),
            runtimeMinutes = record.get("runtime_minutes", Int::class.javaObjectType),
            totalRuntimeMinutes = record.get("total_runtime_minutes", Int::class.javaObjectType),
            episodeCount = record.get("episode_count", Int::class.javaObjectType),
        )
    }.firstOrNull()

    /**
     * Several titles at once, for a caller drawing a list it owns.
     *
     * One query rather than one per id: this is what a watchlist screen and the
     * coverage breakdown both read, and the per-row alternative is the classic
     * way a list of fifty becomes fifty round trips.
     *
     * Ids that do not exist are simply absent from the result. The caller knows
     * what it asked for and can see what came back, which is the only way it can
     * notice that a title it references has been deleted.
     */
    fun findSummaries(titleIds: Collection<UUID>): List<CatalogueTitle> {
        val wanted = titleIds.distinct().take(MAX_SUMMARY_BATCH)
        if (wanted.isEmpty()) return emptyList()

        // Placeholders are generated from the list size and the values are all
        // bound, so nothing here is string-concatenated user input.
        val placeholders = wanted.joinToString(", ") { "?" }
        return dsl.fetch(
            """
                SELECT t.id, t.media_type, t.name, t.original_name, t.overview, t.release_date,
                       t.poster_url, t.popularity_score, t.community_rating, t.metadata_status,
                       m.runtime_minutes, s.total_runtime_minutes, s.episode_count
                  FROM titles t
                  LEFT JOIN movies m ON m.title_id = t.id
                  LEFT JOIN series s ON s.title_id = t.id
                 WHERE t.id IN ($placeholders)
            """.trimIndent(),
            *wanted.toTypedArray(),
        ).map { record ->
            CatalogueTitle(
                id = record.get("id", UUID::class.java),
                mediaType = MediaType.fromDb(record.get("media_type", String::class.java)),
                name = record.get("name", String::class.java),
                originalName = record.get("original_name", String::class.java),
                overview = record.get("overview", String::class.java),
                releaseDate = record.get("release_date", LocalDate::class.java),
                posterUrl = record.get("poster_url", String::class.java),
                popularityScore = record.get("popularity_score", BigDecimal::class.java),
                communityRating = record.get("community_rating", BigDecimal::class.java),
                metadataStatus = MetadataStatus.fromDb(record.get("metadata_status", String::class.java)),
                runtimeMinutes = record.get("runtime_minutes", Int::class.javaObjectType),
                totalRuntimeMinutes = record.get("total_runtime_minutes", Int::class.javaObjectType),
                episodeCount = record.get("episode_count", Int::class.javaObjectType),
            )
        }
    }

    /**
     * Titles whose availability is most overdue, most deserving first.
     *
     * Section 17 wants watchlist titles refreshed daily and the long tail
     * opportunistically, so anything on someone's active watchlist sorts ahead
     * of everything else regardless of staleness. Within each group it is plain
     * staleness ordering, and a title nobody has ever checked sorts first
     * because it has no availability row at all.
     *
     * Only `pending` and `in_progress` items count. Someone who has finished or
     * abandoned a title is not waiting on it, and letting completed rows hold
     * priority would slowly fill the nightly budget with titles nobody intends
     * to watch -- the batch is finite, so a title promoted here is a title
     * demoted somewhere else.
     *
     * This reaches across a module boundary in SQL, which is deliberate and
     * consistent with the `title_availability` join immediately above it: the
     * rule that ArchUnit enforces is that no *class* crosses a feature boundary,
     * because that is the coupling that spreads. Both tables live in one
     * database and one deployment, and doing this in application code would mean
     * paging every title into memory to sort it.
     */
    fun findDueForAvailabilityRefresh(regionCode: String, limit: Int): List<DueTitle> = dsl.fetch(
        """
            SELECT t.id, t.media_type, t.external_id
              FROM titles t
              LEFT JOIN (
                    SELECT title_id, max(source_checked_at) AS last_checked
                      FROM title_availability
                     WHERE region_code = ?
                     GROUP BY title_id
                   ) a ON a.title_id = t.id
             WHERE t.external_source = 'tmdb'
             ORDER BY EXISTS (
                          SELECT 1
                            FROM watchlist_items wi
                           WHERE wi.title_id = t.id
                             AND wi.status IN ('pending', 'in_progress')
                      ) DESC,
                      a.last_checked ASC NULLS FIRST,
                      t.popularity_score DESC NULLS LAST
             LIMIT ?
        """.trimIndent(),
        regionCode,
        limit.coerceIn(1, MAX_REFRESH_BATCH),
    ).map { record ->
        DueTitle(
            titleId = record.get("id", UUID::class.java),
            mediaType = MediaType.fromDb(record.get("media_type", String::class.java)),
            externalId = record.get("external_id", String::class.java),
        )
    }

    data class DueTitle(
        val titleId: UUID,
        val mediaType: MediaType,
        val externalId: String,
    )

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        const val MAX_QUERY_LENGTH = 200
        const val MAX_REFRESH_BATCH = 2_000

        /**
         * A watchlist is meant to be a shortlist. Bounding this keeps one
         * pathological list from generating an unbounded `IN` clause, and the
         * screens that read it page anyway.
         */
        const val MAX_SUMMARY_BATCH = 500
    }
}
