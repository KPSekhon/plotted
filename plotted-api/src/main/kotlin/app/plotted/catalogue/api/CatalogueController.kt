package app.plotted.catalogue.api

import app.plotted.catalogue.domain.CatalogueQueryService
import app.plotted.catalogue.domain.MediaType
import app.plotted.catalogue.domain.TitleIngestionService
import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.error.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Two searches, deliberately separate endpoints.
 *
 * `/titles/search` looks at what Plotted has stored -- fast, works offline, and
 * every result already has a Plotted identifier that a watchlist can reference.
 *
 * `/titles/discover` asks TMDB. Someone adding to a watchlist wants everything
 * that exists, not only what has been ingested, so a result there has no Plotted
 * identifier until it is ingested. Collapsing the two into one endpoint would
 * mean either a slow local search or a confusing mix of results, half of which
 * cannot be added to anything.
 */
@RestController
@RequestMapping("/api/v1/titles")
@Validated
class CatalogueController(
    private val catalogue: CatalogueQueryService,
    private val ingestion: TitleIngestionService,
) {
    @GetMapping("/search")
    @Operation(
        summary = "Search the ingested catalogue",
        description = "Full-text and trigram matching, so a typo still finds the title.",
    )
    fun search(
        @RequestParam @Size(min = 1, max = 200) query: String,
        @RequestParam(required = false) mediaType: MediaType?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<List<TitleResponse>> = ResponseEntity.ok(catalogue.search(query, limit, mediaType).map(TitleResponse::from))

    @GetMapping("/discover")
    @Operation(
        summary = "Search TMDB for titles Plotted may not have yet",
        description =
        "Degrades to an empty list when TMDB is unreachable rather than failing the " +
            "request: an empty search box is a better outcome than an error page.",
    )
    fun discover(@RequestParam @Size(min = 1, max = 200) query: String): ResponseEntity<List<DiscoverResultResponse>> =
        ResponseEntity.ok(ingestion.search(query).map(DiscoverResultResponse::from))

    @GetMapping("/{titleId}")
    @Operation(summary = "A single ingested title")
    fun byId(@PathVariable titleId: UUID): ResponseEntity<TitleResponse> = catalogue.findById(titleId)
        ?.let { ResponseEntity.ok(TitleResponse.from(it)) }
        ?: throw NotFoundException("Title")

    @PostMapping
    @Operation(
        summary = "Ingest a title from TMDB",
        description =
        "Idempotent: ingesting the same title again refreshes it in place and returns 200 " +
            "rather than creating a duplicate. Availability is fetched separately, after " +
            "this returns, so a provider outage cannot fail the ingest.",
    )
    fun ingest(@Valid @RequestBody request: IngestTitleRequest): ResponseEntity<TitleResponse> {
        return when (val outcome = ingestion.ingest(request.mediaType, request.tmdbId)) {
            is TitleIngestionService.IngestionOutcome.Ingested -> {
                val stored = catalogue.findById(outcome.titleId) ?: throw NotFoundException("Title")
                val status = if (outcome.created) HttpStatus.CREATED else HttpStatus.OK
                ResponseEntity.status(status).body(TitleResponse.from(stored))
            }

            is TitleIngestionService.IngestionOutcome.NotFound ->
                throw NotFoundException("TMDB ${request.mediaType.dbValue} ${request.tmdbId}")

            is TitleIngestionService.IngestionOutcome.Failed ->
                // The distinction matters to the caller: one is worth trying
                // again in a moment, the other never will be.
                throw ApiException(
                    if (outcome.retryable) ErrorCode.UPSTREAM_UNAVAILABLE else ErrorCode.INTERNAL_ERROR,
                    "Could not ingest from TMDB: ${outcome.reason}",
                )
        }
    }
}
