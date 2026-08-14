package app.plotted.catalogue.persistence

import app.plotted.catalogue.domain.IngestedSeason
import app.plotted.generated.jooq.tables.references.EPISODES
import app.plotted.generated.jooq.tables.references.SEASONS
import app.plotted.generated.jooq.tables.references.SERIES
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Repository
class SeasonRepository(
    private val dsl: DSLContext,
) {
    /**
     * Stores one season and its episodes, replacing what was there.
     *
     * Idempotent like every other ingestion write: a refresh updates in place
     * rather than duplicating, so re-running the seed is a non-event.
     */
    @Transactional
    fun upsert(seriesTitleId: UUID, season: IngestedSeason): UUID {
        val seasonId = dsl.insertInto(SEASONS)
            .set(SEASONS.ID, UUID.randomUUID())
            .set(SEASONS.SERIES_TITLE_ID, seriesTitleId)
            .set(SEASONS.SEASON_NUMBER, season.seasonNumber)
            .set(SEASONS.NAME, season.name)
            .set(SEASONS.EPISODE_COUNT, season.episodes.size)
            .set(SEASONS.AIR_DATE, season.airDate)
            .onConflict(SEASONS.SERIES_TITLE_ID, SEASONS.SEASON_NUMBER)
            .doUpdate()
            .set(SEASONS.NAME, season.name)
            .set(SEASONS.EPISODE_COUNT, season.episodes.size)
            .set(SEASONS.AIR_DATE, season.airDate)
            .returningResult(SEASONS.ID)
            .fetchOne()
            ?.value1()
            ?: error("Upsert of season ${season.seasonNumber} returned no identifier")

        season.episodes.forEach { episode ->
            dsl.insertInto(EPISODES)
                .set(EPISODES.ID, UUID.randomUUID())
                .set(EPISODES.SEASON_ID, seasonId)
                .set(EPISODES.EPISODE_NUMBER, episode.episodeNumber)
                .set(EPISODES.NAME, episode.name)
                .set(EPISODES.OVERVIEW, episode.overview)
                .set(EPISODES.RUNTIME_MINUTES, episode.runtimeMinutes)
                .set(EPISODES.AIR_DATE, episode.airDate)
                .set(EPISODES.EXTERNAL_ID, episode.externalId)
                .onConflict(EPISODES.SEASON_ID, EPISODES.EPISODE_NUMBER)
                .doUpdate()
                .set(EPISODES.NAME, episode.name)
                .set(EPISODES.OVERVIEW, episode.overview)
                .set(EPISODES.RUNTIME_MINUTES, episode.runtimeMinutes)
                .set(EPISODES.AIR_DATE, episode.airDate)
                .set(EPISODES.EXTERNAL_ID, episode.externalId)
                .execute()
        }

        return seasonId
    }

    /**
     * Replaces the estimated total runtime with one built from actual episodes.
     *
     * Three decisions are baked into this query, and each is a judgement rather
     * than an implementation detail:
     *
     *  * **Season 0 is excluded.** TMDB files specials there. Someone asking
     *    whether they can finish a series before a renewal is not counting the
     *    Christmas special, and including them inflates the commitment estimate
     *    that the optimiser's capacity constraint reads.
     *  * **Missing runtimes fall back to the series average.** TMDB leaves
     *    `runtime` null on plenty of episodes, especially older shows. Summing
     *    only the known ones would silently under-count a series and make it
     *    look like it fits a window it does not.
     *  * **Nothing is written when there is nothing better.** If no episode has
     *    a runtime and there is no average either, the existing estimate stays.
     *    Overwriting it with null would remove the title from every
     *    time-constrained recommendation.
     */
    @Transactional
    fun recalculateTotalRuntime(seriesTitleId: UUID): Int? {
        val measured = dsl.fetchOne(
            """
            SELECT SUM(COALESCE(e.runtime_minutes, s.average_episode_minutes))::int AS total,
                   AVG(e.runtime_minutes)::int                                     AS typical
              FROM episodes e
              JOIN seasons se ON se.id = e.season_id
              JOIN series  s  ON s.title_id = se.series_title_id
             WHERE se.series_title_id = ?
               AND se.season_number > 0
            """.trimIndent(),
            seriesTitleId,
        )

        val total = measured?.get("total", Int::class.javaObjectType)
        if (total == null || total <= 0) return null

        // The typical episode, from the real episodes.
        //
        // This used to come only from TMDB's `episode_run_time`, which is empty
        // for most shows — 181 of 260 seeded series had a total and no episode
        // length. That mattered far more than it looks: Tonight Mode measures a
        // time budget against one sitting, so a series with no episode length
        // cannot be recommended into an evening at all, and two thirds of the
        // catalogue's series were in that state.
        //
        // Derived here for the same reason the total is: the episodes are
        // already stored and they are real, so an average over them beats a
        // field upstream increasingly leaves blank. `AVG` ignores nulls, so
        // this is the mean of the episodes whose runtime is actually known.
        //
        // A mean rather than a median because the column says average and a
        // median hiding in it would be exactly the quiet mismatch this codebase
        // keeps finding. Specials are already excluded by `season_number > 0`,
        // which is what a median would mostly have protected against.
        val typical = measured.get("typical", Int::class.javaObjectType)

        val update = dsl.update(SERIES)
            .set(SERIES.TOTAL_RUNTIME_MINUTES, total)
            .set(
                SERIES.EPISODE_COUNT,
                dsl.selectCount()
                    .from(EPISODES)
                    .join(SEASONS).on(SEASONS.ID.eq(EPISODES.SEASON_ID))
                    .where(SEASONS.SERIES_TITLE_ID.eq(seriesTitleId))
                    .and(SEASONS.SEASON_NUMBER.gt(0))
                    .asField<Int>(),
            )

        // Added only when there is something better to say. Setting it to null
        // would remove the title from every time-constrained recommendation,
        // which is the failure this whole change exists to undo.
        val withTypical =
            if (typical != null && typical > 0) update.set(SERIES.AVERAGE_EPISODE_MINUTES, typical) else update

        withTypical.where(SERIES.TITLE_ID.eq(seriesTitleId)).execute()

        return total
    }

    fun countSeasons(seriesTitleId: UUID): Int = dsl.fetchCount(SEASONS, SEASONS.SERIES_TITLE_ID.eq(seriesTitleId))

    fun countEpisodes(seriesTitleId: UUID): Int = dsl.selectCount()
        .from(EPISODES)
        .join(SEASONS).on(SEASONS.ID.eq(EPISODES.SEASON_ID))
        .where(SEASONS.SERIES_TITLE_ID.eq(seriesTitleId))
        .fetchOne(0, Int::class.java) ?: 0

    fun episodeExists(seriesTitleId: UUID, seasonNumber: Int, episodeNumber: Int): Boolean = dsl.fetchExists(
        dsl.selectOne()
            .from(EPISODES)
            .join(SEASONS).on(SEASONS.ID.eq(EPISODES.SEASON_ID))
            .where(SEASONS.SERIES_TITLE_ID.eq(seriesTitleId))
            .and(SEASONS.SEASON_NUMBER.eq(seasonNumber))
            .and(EPISODES.EPISODE_NUMBER.eq(episodeNumber)),
    )

    /**
     * The first episode of the main run after a position, or the first of all
     * when [afterSeason] is null.
     *
     * Ordered by season then episode, which is the order somebody watches in
     * rather than the order the rows happen to come back in.
     */
    fun nextEpisode(seriesTitleId: UUID, afterSeason: Int?, afterEpisode: Int?, today: LocalDate): NextEpisode? = dsl
        .select(EPISODES.ID, SEASONS.SEASON_NUMBER, EPISODES.EPISODE_NUMBER, EPISODES.NAME, EPISODES.RUNTIME_MINUTES)
        .from(EPISODES)
        .join(SEASONS).on(SEASONS.ID.eq(EPISODES.SEASON_ID))
        .where(SEASONS.SERIES_TITLE_ID.eq(seriesTitleId))
        .and(mainRunAfter(afterSeason, afterEpisode))
        .and(hasAired(today))
        .orderBy(SEASONS.SEASON_NUMBER.asc(), EPISODES.EPISODE_NUMBER.asc())
        .limit(1)
        .fetchOne()
        ?.let {
            NextEpisode(
                episodeId = it[EPISODES.ID]!!,
                seasonNumber = it[SEASONS.SEASON_NUMBER]!!,
                episodeNumber = it[EPISODES.EPISODE_NUMBER]!!,
                name = it[EPISODES.NAME],
                runtimeMinutes = it[EPISODES.RUNTIME_MINUTES],
            )
        }

    /**
     * What is left after a position: how many aired episodes, and how long.
     *
     * The runtime sum skips episodes with no runtime rather than substituting an
     * average, while the count does not -- so "9 episodes, 3 h 12 m" can mean
     * nine episodes of which seven are measured. That is the honest pair.
     * Inventing runtimes here would put a made-up number into a sentence about
     * whether somebody can finish before a removal date.
     */
    fun remaining(seriesTitleId: UUID, afterSeason: Int?, afterEpisode: Int?, today: LocalDate): RemainingEpisodes = dsl
        .select(DSL.count(), DSL.sum(EPISODES.RUNTIME_MINUTES))
        .from(EPISODES)
        .join(SEASONS).on(SEASONS.ID.eq(EPISODES.SEASON_ID))
        .where(SEASONS.SERIES_TITLE_ID.eq(seriesTitleId))
        .and(mainRunAfter(afterSeason, afterEpisode))
        .and(hasAired(today))
        .fetchOne()
        ?.let { RemainingEpisodes(episodes = it.value1(), minutes = it.value2()?.toInt()) }
        ?: RemainingEpisodes(0, null)

    /**
     * Strictly after (season, episode) in watch order, and never a special.
     *
     * Season 0 is where TMDB files specials, excluded for the same reason
     * [recalculateTotalRuntime] excludes it: somebody's place in the story is not
     * the Christmas episode. Written as the lexicographic comparison it actually
     * is -- a later season, or the same season and a later episode.
     */
    private fun mainRunAfter(afterSeason: Int?, afterEpisode: Int?): Condition {
        val mainRun = SEASONS.SEASON_NUMBER.gt(0)
        if (afterSeason == null || afterEpisode == null) return mainRun
        return mainRun.and(
            SEASONS.SEASON_NUMBER.gt(afterSeason)
                .or(SEASONS.SEASON_NUMBER.eq(afterSeason).and(EPISODES.EPISODE_NUMBER.gt(afterEpisode))),
        )
    }

    /**
     * Aired, or undated.
     *
     * A null air date is a gap in Plotted's data rather than evidence that an
     * episode is unreleased, and treating it as unreleased would hide most older
     * series from "what is next" entirely.
     */
    private fun hasAired(today: LocalDate): Condition = EPISODES.AIR_DATE.isNull.or(EPISODES.AIR_DATE.le(today))

    data class NextEpisode(
        val episodeId: UUID,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val name: String?,
        val runtimeMinutes: Int?,
    )

    data class RemainingEpisodes(val episodes: Int, val minutes: Int?)
}
