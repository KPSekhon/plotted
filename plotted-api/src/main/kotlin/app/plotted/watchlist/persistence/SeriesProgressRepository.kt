package app.plotted.watchlist.persistence

import app.plotted.generated.jooq.tables.references.USER_SERIES_PROGRESS
import app.plotted.watchlist.domain.SeriesProgress
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Where the user has got to, per series.
 *
 * Every method is scoped by user id in the query rather than by a check the
 * caller could forget, which is the same rule `WatchlistRepository` follows.
 */
@Repository
class SeriesProgressRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    /**
     * Records a position, replacing whatever was there.
     *
     * An upsert rather than an insert because "I finished episode 8" is a
     * statement about now, not an event to append. Moving *backwards* is allowed
     * and deliberate: correcting a mistake, or starting a rewatch, are both
     * things people do, and a progress marker that only ratchets forward is one
     * they cannot fix.
     */
    fun record(userId: UUID, seriesTitleId: UUID, seasonNumber: Int, episodeNumber: Int, source: String = "user") {
        val now = OffsetDateTime.now(clock)
        dsl.insertInto(USER_SERIES_PROGRESS)
            .set(USER_SERIES_PROGRESS.USER_ID, userId)
            .set(USER_SERIES_PROGRESS.SERIES_TITLE_ID, seriesTitleId)
            .set(USER_SERIES_PROGRESS.LAST_COMPLETED_SEASON_NUMBER, seasonNumber)
            .set(USER_SERIES_PROGRESS.LAST_COMPLETED_EPISODE_NUMBER, episodeNumber)
            .set(USER_SERIES_PROGRESS.SOURCE, source)
            .set(USER_SERIES_PROGRESS.UPDATED_AT, now)
            .onConflict(USER_SERIES_PROGRESS.USER_ID, USER_SERIES_PROGRESS.SERIES_TITLE_ID)
            .doUpdate()
            .set(USER_SERIES_PROGRESS.LAST_COMPLETED_SEASON_NUMBER, seasonNumber)
            .set(USER_SERIES_PROGRESS.LAST_COMPLETED_EPISODE_NUMBER, episodeNumber)
            .set(USER_SERIES_PROGRESS.SOURCE, source)
            .set(USER_SERIES_PROGRESS.UPDATED_AT, now)
            .execute()
    }

    /** Forgets a position entirely, which is how somebody starts again from nothing. */
    fun clear(userId: UUID, seriesTitleId: UUID): Boolean = dsl.deleteFrom(USER_SERIES_PROGRESS)
        .where(USER_SERIES_PROGRESS.USER_ID.eq(userId))
        .and(USER_SERIES_PROGRESS.SERIES_TITLE_ID.eq(seriesTitleId))
        .execute() > 0

    fun find(userId: UUID, seriesTitleId: UUID): SeriesProgress? = dsl.selectFrom(USER_SERIES_PROGRESS)
        .where(USER_SERIES_PROGRESS.USER_ID.eq(userId))
        .and(USER_SERIES_PROGRESS.SERIES_TITLE_ID.eq(seriesTitleId))
        .fetchOne()
        ?.let {
            SeriesProgress(
                seriesTitleId = it.seriesTitleId!!,
                lastCompletedSeasonNumber = it.lastCompletedSeasonNumber!!,
                lastCompletedEpisodeNumber = it.lastCompletedEpisodeNumber!!,
                updatedAt = it.updatedAt!!.toInstant(),
            )
        }

    /**
     * Every position this user has, by series id.
     *
     * One query rather than one per title: Tonight resolves the next episode for
     * a whole candidate list at once, and doing that a row at a time is how a
     * recommendation endpoint acquires an N+1.
     */
    fun findAll(userId: UUID): Map<UUID, SeriesProgress> = dsl.selectFrom(USER_SERIES_PROGRESS)
        .where(USER_SERIES_PROGRESS.USER_ID.eq(userId))
        .fetch()
        .associate {
            it.seriesTitleId!! to SeriesProgress(
                seriesTitleId = it.seriesTitleId!!,
                lastCompletedSeasonNumber = it.lastCompletedSeasonNumber!!,
                lastCompletedEpisodeNumber = it.lastCompletedEpisodeNumber!!,
                updatedAt = it.updatedAt!!.toInstant(),
            )
        }
}
