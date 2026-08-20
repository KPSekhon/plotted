package app.plotted.watchlist.persistence

import app.plotted.generated.jooq.tables.references.EPISODES
import app.plotted.generated.jooq.tables.references.SEASONS
import app.plotted.generated.jooq.tables.references.USER_SERIES_PROGRESS
import app.plotted.watchlist.domain.SeriesProgress
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate
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

    /**
     * The next episode for many series at once, in one query.
     *
     * ### Why this exists rather than a loop
     *
     * Tonight filters on how long the thing it is offering actually runs, which
     * means resolving the next episode for *every* series candidate before the
     * filters run rather than for the three that survive them. A query per
     * series would put an N+1 on the endpoint with the tightest latency budget
     * in the product; `DISTINCT ON` makes it one.
     *
     * ### Why the join is here rather than in `catalogue`
     *
     * It reads `episodes` and `seasons`, which `catalogue` owns. A cross-module
     * *SQL* join is allowed (ADR 0008); a class crossing the boundary is not.
     * The alternative — putting it in `catalogue` — would mean handing a user id
     * to the module that knows about titles, and user state is exactly what
     * `catalogue` is supposed not to have.
     *
     * The `LEFT JOIN` is what makes one query cover both cases: a series with no
     * progress row has no position to be after, so every episode qualifies and
     * the first one wins.
     */
    fun nextEpisodes(userId: UUID, seriesIds: Collection<UUID>): Map<UUID, NextEpisodeRow> {
        if (seriesIds.isEmpty()) return emptyMap()
        val progress = USER_SERIES_PROGRESS
        return dsl
            .selectDistinct(
                SEASONS.SERIES_TITLE_ID,
                EPISODES.ID,
                SEASONS.SEASON_NUMBER,
                EPISODES.EPISODE_NUMBER,
                EPISODES.NAME,
                EPISODES.RUNTIME_MINUTES,
                progress.USER_ID,
            )
            .on(SEASONS.SERIES_TITLE_ID)
            .from(EPISODES)
            .join(SEASONS).on(SEASONS.ID.eq(EPISODES.SEASON_ID))
            .leftJoin(progress)
            .on(progress.SERIES_TITLE_ID.eq(SEASONS.SERIES_TITLE_ID))
            .and(progress.USER_ID.eq(userId))
            .where(SEASONS.SERIES_TITLE_ID.`in`(seriesIds))
            .and(afterRecordedPosition())
            // Ordered by the distinct-on key first, as Postgres requires, then by
            // watch order -- which is what decides which row survives.
            .orderBy(SEASONS.SERIES_TITLE_ID.asc(), SEASONS.SEASON_NUMBER.asc(), EPISODES.EPISODE_NUMBER.asc())
            .fetch()
            .associate {
                it[SEASONS.SERIES_TITLE_ID]!! to NextEpisodeRow(
                    episodeId = it[EPISODES.ID]!!,
                    seasonNumber = it[SEASONS.SEASON_NUMBER]!!,
                    episodeNumber = it[EPISODES.EPISODE_NUMBER]!!,
                    name = it[EPISODES.NAME],
                    runtimeMinutes = it[EPISODES.RUNTIME_MINUTES],
                    // Null user id means the left join found no progress row, so
                    // this is episode one rather than a resumption.
                    started = it[progress.USER_ID] != null,
                )
            }
    }

    /** How much is left, for many series at once. Same predicates, one aggregate. */
    fun remainingFor(userId: UUID, seriesIds: Collection<UUID>): Map<UUID, RemainingRow> {
        if (seriesIds.isEmpty()) return emptyMap()
        val progress = USER_SERIES_PROGRESS
        return dsl
            .select(SEASONS.SERIES_TITLE_ID, DSL.count(), DSL.sum(EPISODES.RUNTIME_MINUTES))
            .from(EPISODES)
            .join(SEASONS).on(SEASONS.ID.eq(EPISODES.SEASON_ID))
            .leftJoin(progress)
            .on(progress.SERIES_TITLE_ID.eq(SEASONS.SERIES_TITLE_ID))
            .and(progress.USER_ID.eq(userId))
            .where(SEASONS.SERIES_TITLE_ID.`in`(seriesIds))
            .and(afterRecordedPosition())
            .groupBy(SEASONS.SERIES_TITLE_ID)
            .fetch()
            .associate {
                it[SEASONS.SERIES_TITLE_ID]!! to RemainingRow(
                    episodes = it.value2(),
                    minutes = it.value3()?.toInt(),
                )
            }
    }

    /**
     * Aired, in the main run, and after whatever position was recorded.
     *
     * The three rules the single-series queries follow, written once so the
     * batched path cannot drift from them: specials never count, an unaired
     * episode is not next, and an undated one is (a missing date is a gap in
     * Plotted's data rather than evidence of an unreleased episode).
     */
    private fun afterRecordedPosition(): Condition {
        val progress = USER_SERIES_PROGRESS
        val today = LocalDate.now(clock)
        return SEASONS.SEASON_NUMBER.gt(0)
            .and(EPISODES.AIR_DATE.isNull.or(EPISODES.AIR_DATE.le(today)))
            .and(
                progress.USER_ID.isNull
                    .or(SEASONS.SEASON_NUMBER.gt(progress.LAST_COMPLETED_SEASON_NUMBER))
                    .or(
                        SEASONS.SEASON_NUMBER.eq(progress.LAST_COMPLETED_SEASON_NUMBER)
                            .and(EPISODES.EPISODE_NUMBER.gt(progress.LAST_COMPLETED_EPISODE_NUMBER)),
                    ),
            )
    }

    data class NextEpisodeRow(
        val episodeId: UUID,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val name: String?,
        val runtimeMinutes: Int?,
        val started: Boolean,
    )

    data class RemainingRow(val episodes: Int, val minutes: Int?)
}
