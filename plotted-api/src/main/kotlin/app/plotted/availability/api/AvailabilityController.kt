package app.plotted.availability.api

import app.plotted.availability.domain.AvailabilityQueryService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/titles/{titleId}/availability")
class AvailabilityController(
    private val availability: AvailabilityQueryService,
) {
    @GetMapping
    @Operation(
        summary = "Where a title can be watched",
        description =
        "Always answers, even when the data is old. Staleness is reported rather than " +
            "hidden: the degraded behaviour Plotted promises is cached availability with a " +
            "visible timestamp, not a pretence of knowing nothing.",
    )
    fun byTitle(@PathVariable titleId: UUID): ResponseEntity<AvailabilityResponse> {
        val result = availability.forTitle(titleId)
        return ResponseEntity.ok(
            AvailabilityResponse(
                regionCode = result.regionCode,
                offers = result.offers.map(AvailabilityOfferResponse::from),
                lastVerifiedAt = result.lastVerifiedAt,
                stale = result.stale,
                // Required wherever this data is displayed. See docs/data-sources.md.
                attribution = ATTRIBUTION,
            ),
        )
    }

    private companion object {
        const val ATTRIBUTION = "Streaming availability data provided by JustWatch via TMDB."
    }
}
