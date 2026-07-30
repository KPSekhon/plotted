package app.plotted.catalogue.api

import app.plotted.catalogue.domain.CatalogueTitle
import app.plotted.catalogue.domain.MediaType
import app.plotted.catalogue.domain.TitleSearchResult
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Schema(description = "A title Plotted has already ingested.")
data class TitleResponse(
    val id: UUID,
    val mediaType: String,
    val name: String,
    val originalName: String?,
    val overview: String?,
    val releaseDate: LocalDate?,
    val posterUrl: String?,
    val communityRating: BigDecimal?,
    @field:Schema(
        description =
        "How long this takes to watch: a film's runtime, or a series' estimated total. " +
            "Null means Plotted does not know, and a time-constrained recommendation must exclude it.",
    )
    val watchMinutes: Int?,
    val episodeCount: Int?,
    @field:Schema(description = "stub, partial, complete or failed. Anything but complete is missing something.")
    val metadataStatus: String,
) {
    companion object {
        fun from(title: CatalogueTitle): TitleResponse = TitleResponse(
            id = title.id,
            mediaType = title.mediaType.dbValue,
            name = title.name,
            originalName = title.originalName,
            overview = title.overview,
            releaseDate = title.releaseDate,
            posterUrl = title.posterUrl,
            communityRating = title.communityRating,
            watchMinutes = title.watchMinutes,
            episodeCount = title.episodeCount,
            metadataStatus = title.metadataStatus.dbValue,
        )
    }
}

@Schema(
    description =
    "A result from TMDB, which Plotted may not have ingested yet. Add it with " +
        "POST /api/v1/titles to get a Plotted identifier.",
)
data class DiscoverResultResponse(
    val externalId: String,
    val mediaType: String,
    val name: String,
    val releaseDate: LocalDate?,
    val overview: String?,
    val posterUrl: String?,
) {
    companion object {
        fun from(result: TitleSearchResult): DiscoverResultResponse = DiscoverResultResponse(
            externalId = result.externalId,
            mediaType = result.mediaType.dbValue,
            name = result.name,
            releaseDate = result.releaseDate,
            overview = result.overview,
            posterUrl = result.posterUrl,
        )
    }
}

@Schema(description = "Ingest a title from TMDB into the Plotted catalogue.")
data class IngestTitleRequest(
    @field:NotNull
    val mediaType: MediaType,
    @field:Min(1)
    val tmdbId: Int,
)
