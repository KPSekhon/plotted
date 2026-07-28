package app.plotted.catalogue.persistence

import app.plotted.catalogue.domain.IngestedTitle
import app.plotted.catalogue.domain.MediaType
import app.plotted.generated.jooq.tables.references.GENRES
import app.plotted.generated.jooq.tables.references.MOVIES
import app.plotted.generated.jooq.tables.references.SERIES
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.TITLE_GENRES
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Catalogue persistence.
 *
 * Every write here is idempotent. Ingestion re-runs constantly -- nightly
 * refreshes, a user adding a title someone else already added, a resumed import
 * -- and "run it twice" has to be a non-event rather than a duplicate row.
 */
@Repository
class TitleRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Inserts or refreshes a title and its type-specific row and genre links.
     *
     * The whole title is one transaction: a `titles` row with no matching
     * `movies` row would be a title with no runtime, which silently disappears
     * from every time-constrained recommendation rather than failing loudly.
     */
    @Transactional
    fun upsert(title: IngestedTitle): UpsertResult {
        val now = OffsetDateTime.now(clock)
        val existingId = findIdByExternalId(title.externalId)

        val titleId = dsl.insertInto(TITLES)
            .set(TITLES.ID, existingId ?: UUID.randomUUID())
            .set(TITLES.EXTERNAL_SOURCE, EXTERNAL_SOURCE)
            .set(TITLES.EXTERNAL_ID, title.externalId)
            .set(TITLES.MEDIA_TYPE, title.mediaType.dbValue)
            .set(TITLES.NAME, title.name)
            .set(TITLES.ORIGINAL_NAME, title.originalName)
            .set(TITLES.OVERVIEW, title.overview)
            .set(TITLES.RELEASE_DATE, title.releaseDate)
            .set(TITLES.ORIGINAL_LANGUAGE, title.originalLanguage)
            .set(TITLES.POSTER_URL, title.posterUrl)
            .set(TITLES.BACKDROP_URL, title.backdropUrl)
            .set(TITLES.POPULARITY_SCORE, title.popularityScore)
            .set(TITLES.COMMUNITY_RATING, title.communityRating)
            .set(TITLES.VOTE_COUNT, title.voteCount)
            .set(TITLES.METADATA_STATUS, title.metadataStatus.dbValue)
            .set(TITLES.METADATA_UPDATED_AT, now)
            .set(TITLES.CREATED_AT, now)
            .set(TITLES.UPDATED_AT, now)
            .onConflict(TITLES.EXTERNAL_SOURCE, TITLES.EXTERNAL_ID)
            .doUpdate()
            .set(TITLES.MEDIA_TYPE, title.mediaType.dbValue)
            .set(TITLES.NAME, title.name)
            .set(TITLES.ORIGINAL_NAME, title.originalName)
            .set(TITLES.OVERVIEW, title.overview)
            .set(TITLES.RELEASE_DATE, title.releaseDate)
            .set(TITLES.ORIGINAL_LANGUAGE, title.originalLanguage)
            .set(TITLES.POSTER_URL, title.posterUrl)
            .set(TITLES.BACKDROP_URL, title.backdropUrl)
            .set(TITLES.POPULARITY_SCORE, title.popularityScore)
            .set(TITLES.COMMUNITY_RATING, title.communityRating)
            .set(TITLES.VOTE_COUNT, title.voteCount)
            .set(TITLES.METADATA_STATUS, title.metadataStatus.dbValue)
            .set(TITLES.METADATA_UPDATED_AT, now)
            .set(TITLES.UPDATED_AT, now)
            // created_at is deliberately absent: it records when Plotted first
            // saw the title, and a refresh must not rewrite that.
            .returningResult(TITLES.ID)
            .fetchOne()
            ?.value1()
            ?: error("Upsert of title ${title.externalId} returned no identifier")

        when (title.mediaType) {
            MediaType.MOVIE -> upsertMovie(titleId, title)
            MediaType.SERIES -> upsertSeries(titleId, title)
        }

        val unknownGenreIds = replaceGenres(titleId, title.genreIds)

        return UpsertResult(
            titleId = titleId,
            created = existingId == null,
            unknownGenreIds = unknownGenreIds,
        )
    }

    fun findIdByExternalId(externalId: String): UUID? = dsl.select(TITLES.ID)
        .from(TITLES)
        .where(TITLES.EXTERNAL_SOURCE.eq(EXTERNAL_SOURCE))
        .and(TITLES.EXTERNAL_ID.eq(externalId))
        .fetchOne()
        ?.value1()

    fun countTitles(): Int = dsl.fetchCount(TITLES)

    private fun upsertMovie(titleId: UUID, title: IngestedTitle) {
        val details = title.movie ?: return
        dsl.insertInto(MOVIES)
            .set(MOVIES.TITLE_ID, titleId)
            .set(MOVIES.RUNTIME_MINUTES, details.runtimeMinutes)
            .onConflict(MOVIES.TITLE_ID)
            .doUpdate()
            .set(MOVIES.RUNTIME_MINUTES, details.runtimeMinutes)
            .execute()
    }

    private fun upsertSeries(titleId: UUID, title: IngestedTitle) {
        val details = title.series ?: return
        dsl.insertInto(SERIES)
            .set(SERIES.TITLE_ID, titleId)
            .set(SERIES.STATUS, details.status)
            .set(SERIES.FIRST_AIR_DATE, details.firstAirDate)
            .set(SERIES.LAST_AIR_DATE, details.lastAirDate)
            .set(SERIES.SEASON_COUNT, details.seasonCount)
            .set(SERIES.EPISODE_COUNT, details.episodeCount)
            .set(SERIES.AVERAGE_EPISODE_MINUTES, details.averageEpisodeMinutes)
            .set(SERIES.TOTAL_RUNTIME_MINUTES, details.totalRuntimeMinutes)
            .onConflict(SERIES.TITLE_ID)
            .doUpdate()
            .set(SERIES.STATUS, details.status)
            .set(SERIES.FIRST_AIR_DATE, details.firstAirDate)
            .set(SERIES.LAST_AIR_DATE, details.lastAirDate)
            .set(SERIES.SEASON_COUNT, details.seasonCount)
            .set(SERIES.EPISODE_COUNT, details.episodeCount)
            .set(SERIES.AVERAGE_EPISODE_MINUTES, details.averageEpisodeMinutes)
            .set(SERIES.TOTAL_RUNTIME_MINUTES, details.totalRuntimeMinutes)
            .execute()
    }

    /**
     * Makes the stored genre links match the supplied set exactly, including
     * removing links TMDB has dropped. Re-genring happens, and a stale link
     * quietly biases genre affinity for every user who has the title.
     *
     * Unknown genre ids are skipped rather than inserted. `genres` is seeded
     * from TMDB's own list, so an unknown id means TMDB added a genre and the
     * seed needs updating; failing the whole ingestion over one link would be a
     * worse outcome than a logged warning.
     */
    private fun replaceGenres(titleId: UUID, genreIds: List<Int>): List<Int> {
        val requested = genreIds.distinct()
        val known = if (requested.isEmpty()) {
            emptySet()
        } else {
            dsl.select(GENRES.ID)
                .from(GENRES)
                .where(GENRES.ID.`in`(requested.map { it.toShort() }))
                .fetch()
                .mapNotNull { it.value1()?.toInt() }
                .toSet()
        }

        val unknown = requested.filterNot { it in known }
        if (unknown.isNotEmpty()) {
            log.warn(
                "Title {} references genre ids {} that are not in the seeded genre list; links skipped",
                titleId,
                unknown,
            )
        }

        val stale = if (known.isEmpty()) {
            TITLE_GENRES.TITLE_ID.eq(titleId)
        } else {
            TITLE_GENRES.TITLE_ID.eq(titleId)
                .and(TITLE_GENRES.GENRE_ID.notIn(known.map { it.toShort() }))
        }
        dsl.deleteFrom(TITLE_GENRES).where(stale).execute()

        if (known.isNotEmpty()) {
            dsl.batch(
                known.map { genreId ->
                    dsl.insertInto(TITLE_GENRES)
                        .set(TITLE_GENRES.TITLE_ID, titleId)
                        .set(TITLE_GENRES.GENRE_ID, genreId.toShort())
                        .onConflictDoNothing()
                },
            ).execute()
        }

        return unknown
    }

    data class UpsertResult(
        val titleId: UUID,
        val created: Boolean,
        val unknownGenreIds: List<Int>,
    )

    private companion object {
        const val EXTERNAL_SOURCE = "tmdb"
    }
}
