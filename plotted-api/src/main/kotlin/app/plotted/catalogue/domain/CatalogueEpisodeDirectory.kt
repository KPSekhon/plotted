package app.plotted.catalogue.domain

import app.plotted.catalogue.persistence.SeasonRepository
import app.plotted.platform.spi.EpisodeDirectory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Catalogue's side of the [EpisodeDirectory] contract.
 *
 * Thin, like the other adapters. The one judgement it makes is supplying
 * *today*: what counts as aired is a question about the clock rather than about
 * the caller, and letting `watchlist` pass a date in would let two callers
 * disagree about which episodes exist yet.
 */
@Component
class CatalogueEpisodeDirectory(
    private val seasons: SeasonRepository,
    private val clock: Clock,
) : EpisodeDirectory {
    override fun episodeExists(seriesTitleId: UUID, seasonNumber: Int, episodeNumber: Int): Boolean =
        seasons.episodeExists(seriesTitleId, seasonNumber, episodeNumber)

    override fun nextEpisode(seriesTitleId: UUID, afterSeason: Int?, afterEpisode: Int?): EpisodeDirectory.Episode? =
        seasons.nextEpisode(seriesTitleId, afterSeason, afterEpisode, today())?.let {
            EpisodeDirectory.Episode(
                episodeId = it.episodeId,
                seasonNumber = it.seasonNumber,
                episodeNumber = it.episodeNumber,
                name = it.name,
                runtimeMinutes = it.runtimeMinutes,
            )
        }

    override fun remaining(seriesTitleId: UUID, afterSeason: Int?, afterEpisode: Int?): EpisodeDirectory.Remaining =
        seasons.remaining(seriesTitleId, afterSeason, afterEpisode, today()).let {
            EpisodeDirectory.Remaining(episodes = it.episodes, minutes = it.minutes)
        }

    private fun today(): LocalDate = LocalDate.now(clock)
}
