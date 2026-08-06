package app.plotted.watchlist.api

import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.security.currentUser
import app.plotted.watchlist.domain.CoverageService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/watchlist/coverage")
class CoverageController(
    private val coverage: CoverageService,
    private val properties: TmdbProperties,
) {
    @GetMapping
    @Operation(
        summary = "Which service covers the most of the watchlist",
        description =
        "Shares are weighted by priority rather than counted, so one title someone urgently " +
            "wants outranks several they are lukewarm about. Only outstanding items count: " +
            "something already watched says nothing about which service to pay for next month. " +
            "Titles whose availability has never been checked are excluded and reported in " +
            "unknownTitles, because scoring them as uncovered would penalise every service in " +
            "proportion to how stale Plotted's own data is.",
    )
    fun coverage(): ResponseEntity<CoverageResponse> {
        val report = coverage.forUser(currentUser().userId, properties.region)
        return ResponseEntity.ok(CoverageResponse.from(report, ATTRIBUTION))
    }

    private companion object {
        const val ATTRIBUTION = "Streaming availability data provided by JustWatch via TMDB."
    }
}
