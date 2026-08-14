package app.plotted.watchlist.api

import app.plotted.watchlist.domain.CoverageService
import app.plotted.watchlist.domain.Priority
import app.plotted.watchlist.domain.SeriesView
import app.plotted.watchlist.domain.WatchlistService
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "A title on the signed-in user's watchlist.")
data class WatchlistItemResponse(
    val id: UUID,
    val titleId: UUID,
    @Schema(description = "1 is the highest priority and 5 the lowest.")
    val priority: Int,
    val status: String,
    val addedAt: Instant,
    @Schema(
        description =
        "When this item became completed. Null unless status is completed, and also null on a " +
            "completed item whose transition predates this field -- unknown, rather than zero.",
    )
    val completedAt: Instant?,
    val desiredByDate: LocalDate?,
    val notes: String?,
    @Schema(
        description =
        "True when this title is also blocked. It keeps its place on the list rather than being " +
            "deleted, but neither recommender will offer it while the block stands.",
    )
    val blocked: Boolean,
    @Schema(description = "Null when the title has been removed from the catalogue since it was added.")
    val title: WatchlistTitleResponse?,
) {
    companion object {
        fun from(entry: WatchlistService.WatchlistEntry): WatchlistItemResponse = WatchlistItemResponse(
            id = entry.item.id,
            titleId = entry.item.titleId,
            priority = entry.item.priority.value,
            status = entry.item.status.dbValue,
            addedAt = entry.item.addedAt,
            completedAt = entry.item.completedAt,
            desiredByDate = entry.item.desiredByDate,
            notes = entry.item.notes,
            blocked = entry.blocked,
            title = entry.title?.let {
                WatchlistTitleResponse(
                    name = it.name,
                    mediaType = it.mediaType,
                    releaseYear = it.releaseYear,
                    posterUrl = it.posterUrl,
                    watchMinutes = it.watchMinutes,
                )
            },
        )
    }
}

data class WatchlistTitleResponse(
    val name: String,
    val mediaType: String,
    val releaseYear: Int?,
    val posterUrl: String?,
    @Schema(description = "Runtime for a film, total episode runtime for a series. Null when unknown.")
    val watchMinutes: Int?,
)

data class WatchlistResponse(
    val id: UUID,
    val name: String,
    val items: List<WatchlistItemResponse>,
) {
    companion object {
        fun from(view: WatchlistService.WatchlistView): WatchlistResponse = WatchlistResponse(
            id = view.watchlist.id,
            name = view.watchlist.name,
            items = view.entries.map(WatchlistItemResponse::from),
        )
    }
}

@Schema(
    description =
    "A title the user has asked never to be recommended. Blocking suppresses Tonight Mode and the " +
        "subscription optimiser; it does not hide the title from catalogue search.",
)
data class BlockedTitleResponse(
    val titleId: UUID,
    @Schema(description = "Why they blocked it, if they said. Free text, never interpreted.")
    val reason: String?,
    val blockedAt: Instant,
    @Schema(description = "Null when the title has been removed from the catalogue since it was blocked.")
    val title: WatchlistTitleResponse?,
) {
    companion object {
        fun from(entry: WatchlistService.BlockedEntry): BlockedTitleResponse = BlockedTitleResponse(
            titleId = entry.blocked.titleId,
            reason = entry.blocked.reason,
            blockedAt = entry.blocked.createdAt,
            title = entry.title?.let {
                WatchlistTitleResponse(
                    name = it.name,
                    mediaType = it.mediaType,
                    releaseYear = it.releaseYear,
                    posterUrl = it.posterUrl,
                    watchMinutes = it.watchMinutes,
                )
            },
        )
    }
}

data class BlockedTitlesResponse(
    val blocked: List<BlockedTitleResponse>,
)

data class BlockTitleRequest(
    @field:NotNull
    val titleId: UUID?,
    // Bounded to the column width so an over-long reason is a 400 naming the
    // field rather than a 500 from the database truncating nothing quietly.
    @field:Size(max = 64)
    @Schema(description = "Optional free-text note. Stored, shown back, never interpreted.")
    val reason: String? = null,
)

data class AddWatchlistItemRequest(
    @field:NotNull
    val titleId: UUID?,
    @field:Min(Priority.HIGHEST.toLong())
    @field:Max(Priority.LOWEST.toLong())
    @Schema(description = "1 is the highest priority and 5 the lowest. Defaults to 3.")
    val priority: Int? = null,
    val desiredByDate: LocalDate? = null,
    @field:Size(max = 2_000)
    val notes: String? = null,
)

data class UpdateWatchlistItemRequest(
    @field:Min(Priority.HIGHEST.toLong())
    @field:Max(Priority.LOWEST.toLong())
    val priority: Int? = null,
    @Schema(
        description = "One of pending, in_progress, completed, abandoned, unavailable.",
        allowableValues = ["pending", "in_progress", "completed", "abandoned", "unavailable"],
    )
    val status: String? = null,
    val desiredByDate: LocalDate? = null,
    @Schema(description = "Set true to remove an existing desiredByDate; JSON null alone cannot express this.")
    val clearDesiredByDate: Boolean = false,
    @field:Size(max = 2_000)
    val notes: String? = null,
    @Schema(description = "Set true to remove existing notes.")
    val clearNotes: Boolean = false,
)

@Schema(
    description =
    "Which service covers the largest priority-weighted share of the outstanding watchlist. " +
        "Titles whose availability has never been checked are excluded from the calculation and " +
        "reported in unknownTitles, so a stale catalogue does not silently depress every score.",
)
data class CoverageResponse(
    val regionCode: String,
    @Schema(description = "Outstanding items with availability data. The denominator of every share below.")
    val consideredTitles: Int,
    @Schema(description = "Outstanding items nobody has checked yet. Reported, never scored.")
    val unknownTitles: Int,
    val providers: List<ProviderCoverageResponse>,
    val attribution: String,
) {
    companion object {
        fun from(report: CoverageService.CoverageReport, attribution: String): CoverageResponse = CoverageResponse(
            regionCode = report.regionCode,
            consideredTitles = report.consideredTitles,
            unknownTitles = report.unknownTitles,
            providers = report.providers.map(ProviderCoverageResponse::from),
            attribution = attribution,
        )
    }
}

data class ProviderCoverageResponse(
    val providerId: UUID,
    val name: String,
    val slug: String,
    val logoUrl: String?,
    val titleCount: Int,
    @Schema(description = "Share of the total priority weight this service covers, 0 to 1.")
    val weightedShare: Double,
    val titles: List<CoveredTitleResponse>,
) {
    companion object {
        fun from(coverage: CoverageService.ProviderCoverage): ProviderCoverageResponse = ProviderCoverageResponse(
            providerId = coverage.providerId,
            name = coverage.name,
            slug = coverage.slug,
            logoUrl = coverage.logoUrl,
            titleCount = coverage.titleCount,
            weightedShare = coverage.weightedShare,
            titles = coverage.titles.map { CoveredTitleResponse(it.titleId, it.name, it.priority) },
        )
    }
}

data class CoveredTitleResponse(
    val titleId: UUID,
    val name: String?,
    val priority: Int,
)

@Schema(
    description =
    "Where you are in a series and what comes next. Position only: this records which episode " +
        "you finished, never how quickly you got there, so nothing built on it may claim a " +
        "viewing pace.",
)
data class SeriesProgressResponse(
    val titleId: UUID,
    @Schema(description = "Null until you have finished something. Absent history, not episode zero.")
    val lastCompleted: EpisodeRefResponse?,
    @Schema(
        description =
        "The first aired episode you have not finished, or episode one when nothing is recorded. " +
            "Null only when there is nothing left to watch.",
    )
    val next: NextEpisodeResponse?,
    @Schema(description = "Aired episodes still ahead of you, and how long they run.")
    val remaining: RemainingResponse,
    @Schema(description = "True when nothing aired is left. Different from not having started.")
    val caughtUp: Boolean,
    val updatedAt: Instant?,
) {
    companion object {
        fun from(view: SeriesView) = SeriesProgressResponse(
            titleId = view.seriesTitleId,
            lastCompleted = view.progress?.let {
                EpisodeRefResponse(it.lastCompletedSeasonNumber, it.lastCompletedEpisodeNumber)
            },
            next = view.next?.let {
                NextEpisodeResponse(
                    episodeId = it.episodeId,
                    seasonNumber = it.seasonNumber,
                    episodeNumber = it.episodeNumber,
                    name = it.name,
                    runtimeMinutes = it.runtimeMinutes,
                )
            },
            remaining = RemainingResponse(view.remaining.episodes, view.remaining.minutes),
            caughtUp = view.caughtUp,
            updatedAt = view.progress?.updatedAt,
        )
    }
}

data class EpisodeRefResponse(val seasonNumber: Int, val episodeNumber: Int)

data class NextEpisodeResponse(
    val episodeId: UUID,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String?,
    @Schema(
        description =
        "This episode's own runtime, or null when upstream never gave one. Not filled in from the " +
            "series average: a fallback presented as a measurement is how a time filter becomes " +
            "precise about something nobody measured.",
    )
    val runtimeMinutes: Int?,
)

@Schema(
    description =
    "The count includes episodes with no known runtime; the minutes do not. So nine episodes and " +
        "three hours can mean nine episodes of which seven are measured, which is the honest pair.",
)
data class RemainingResponse(val episodes: Int, val minutes: Int?)

data class RecordProgressRequest(
    @field:NotNull
    @field:Min(1)
    @Schema(description = "Specials (season 0) are not a place in the story and are refused.")
    val seasonNumber: Int?,
    @field:NotNull
    @field:Min(1)
    val episodeNumber: Int?,
)
