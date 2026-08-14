package app.plotted.watchlist.domain

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.error.NotFoundException
import app.plotted.platform.spi.EpisodeDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.watchlist.persistence.SeriesProgressRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Where somebody is in a series, and therefore what they should watch next.
 *
 * The point of the whole feature is one sentence: Tonight saying **"Chainsaw
 * Man, S1 E8, 24 min"** instead of "Chainsaw Man, about 24 minutes an episode".
 * The second answer sends the user to another app to work out which episode they
 * are on, which is the decision Plotted exists to remove.
 *
 * ### Position, not pace
 *
 * A last-completed episode says *where* somebody is and nothing about how fast
 * they got there. Anything downstream may say "at your configured three hours a
 * week you would finish this before 19 August"; nothing may say "at your current
 * pace", because pace needs completion events over time and this stores one row.
 * The distinction is written into V19 as well, because it is the sort that gets
 * lost the moment somebody sees a timestamp and assumes it is a history.
 */
@Service
class SeriesProgressService(
    private val progress: SeriesProgressRepository,
    private val episodes: EpisodeDirectory,
    private val titles: TitleDirectory,
) {
    /**
     * Records that the user finished an episode.
     *
     * The position is checked against the catalogue rather than trusted: V19
     * stores season and episode numbers rather than a foreign key, so this is
     * the only thing standing between the table and somebody recording that they
     * finished season nine of a three-season show.
     */
    @Transactional
    fun record(userId: UUID, titleId: UUID, seasonNumber: Int, episodeNumber: Int): SeriesView {
        val summary = titles.findSummaries(listOf(titleId)).firstOrNull() ?: throw NotFoundException("Title")
        if (summary.mediaType != SERIES_MEDIA_TYPE) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Progress is only tracked for series, and ${summary.name} is a ${summary.mediaType}.",
            )
        }
        if (!episodes.episodeExists(titleId, seasonNumber, episodeNumber)) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "${summary.name} has no season $seasonNumber episode $episodeNumber.",
                mapOf("episode" to "Not in the catalogue for this series"),
            )
        }

        progress.record(userId, titleId, seasonNumber, episodeNumber)
        return view(userId, titleId)
    }

    /** Forgets the position. Idempotent: clearing what is not there is not a 404. */
    @Transactional
    fun clear(userId: UUID, titleId: UUID): SeriesView {
        progress.clear(userId, titleId)
        return view(userId, titleId)
    }

    /**
     * Where they are and what is next, for one series.
     *
     * With no progress recorded, `next` is the first episode rather than null —
     * "you have not started" and "there is nothing to watch" are different
     * answers, and the first one still has a next episode.
     */
    @Transactional(readOnly = true)
    fun view(userId: UUID, titleId: UUID): SeriesView {
        val current = progress.find(userId, titleId)
        return buildView(titleId, current)
    }

    /**
     * The same, for many series at once.
     *
     * Tonight needs this for a whole candidate list, so the positions come back
     * in one query. The per-series episode lookups are still one each — that is
     * the honest cost of the ordering, and it is bounded by the number of series
     * on a shortlist rather than by the catalogue.
     */
    @Transactional(readOnly = true)
    fun viewAll(userId: UUID, titleIds: Collection<UUID>): Map<UUID, SeriesView> {
        if (titleIds.isEmpty()) return emptyMap()
        val positions = progress.findAll(userId)
        return titleIds.associateWith { buildView(it, positions[it]) }
    }

    private fun buildView(titleId: UUID, current: SeriesProgress?): SeriesView {
        val next = episodes.nextEpisode(titleId, current?.lastCompletedSeasonNumber, current?.lastCompletedEpisodeNumber)
        val remaining = episodes.remaining(titleId, current?.lastCompletedSeasonNumber, current?.lastCompletedEpisodeNumber)
        return SeriesView(seriesTitleId = titleId, progress = current, next = next, remaining = remaining)
    }

    private companion object {
        const val SERIES_MEDIA_TYPE = "series"
    }
}

/** A recorded position. Nothing about how long it took to get there. */
data class SeriesProgress(
    val seriesTitleId: UUID,
    val lastCompletedSeasonNumber: Int,
    val lastCompletedEpisodeNumber: Int,
    val updatedAt: Instant,
)

/**
 * Where they are, what is next, and what is left.
 *
 * [next] is null only when there is genuinely nothing to watch — the series is
 * finished, or everything remaining is unaired. That is a real state and the
 * interface says so rather than showing an empty episode.
 */
data class SeriesView(
    val seriesTitleId: UUID,
    val progress: SeriesProgress?,
    val next: EpisodeDirectory.Episode?,
    val remaining: EpisodeDirectory.Remaining,
) {
    /** Started, in the sense that matters: something has been finished. */
    val started: Boolean get() = progress != null

    /** Nothing aired is left. Distinct from "not started", which also has no history. */
    val caughtUp: Boolean get() = next == null
}
