package app.plotted.catalogue.persistence

import app.plotted.catalogue.domain.IngestedSeason
import app.plotted.generated.jooq.tables.references.EPISODES
import app.plotted.generated.jooq.tables.references.SEASONS
import app.plotted.generated.jooq.tables.references.SERIES
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
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
        val total = dsl.fetchOne(
            """
            SELECT SUM(COALESCE(e.runtime_minutes, s.average_episode_minutes))::int AS total
              FROM episodes e
              JOIN seasons se ON se.id = e.season_id
              JOIN series  s  ON s.title_id = se.series_title_id
             WHERE se.series_title_id = ?
               AND se.season_number > 0
            """.trimIndent(),
            seriesTitleId,
        )?.get("total", Int::class.javaObjectType)

        if (total == null || total <= 0) return null

        dsl.update(SERIES)
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
            .where(SERIES.TITLE_ID.eq(seriesTitleId))
            .execute()

        return total
    }

    fun countSeasons(seriesTitleId: UUID): Int = dsl.fetchCount(SEASONS, SEASONS.SERIES_TITLE_ID.eq(seriesTitleId))

    fun countEpisodes(seriesTitleId: UUID): Int = dsl.selectCount()
        .from(EPISODES)
        .join(SEASONS).on(SEASONS.ID.eq(EPISODES.SEASON_ID))
        .where(SEASONS.SERIES_TITLE_ID.eq(seriesTitleId))
        .fetchOne(0, Int::class.java) ?: 0
}
