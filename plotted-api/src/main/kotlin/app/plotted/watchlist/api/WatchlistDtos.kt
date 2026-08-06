package app.plotted.watchlist.api

import app.plotted.watchlist.domain.CoverageService
import app.plotted.watchlist.domain.Priority
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
    val desiredByDate: LocalDate?,
    val notes: String?,
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
            desiredByDate = entry.item.desiredByDate,
            notes = entry.item.notes,
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
