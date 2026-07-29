package app.plotted.availability.domain

import app.plotted.platform.integration.tmdb.TmdbMediaType
import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.platform.spi.TitleDirectory
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The nightly availability snapshot.
 *
 * Appendix A is emphatic that this runs from day one: Plot Armour's removal-risk
 * model needs six months of history before it can exist, so every night this
 * does not run is a night that can never be recovered. The model is a phase 12
 * item; the collection it depends on starts here.
 *
 * Two things keep it from becoming a problem:
 *
 *  * **A budget.** It refreshes at most [SnapshotJobProperties.batchSize] titles
 *    per run, oldest-checked first. Section 17 is blunt about this -- refreshing
 *    a hundred thousand titles nightly exhausts every API quota there is, and
 *    the quota is not recoverable either.
 *  * **A failure does not stop the run.** One title that cannot be reached is
 *    logged and skipped. The run reports what it managed.
 *
 * Disabled by default so a developer running the application locally does not
 * quietly start spending the quota.
 */
@Component
@ConditionalOnProperty(prefix = "plotted.availability.snapshot", name = ["enabled"], havingValue = "true")
class AvailabilitySnapshotJob(
    private val titles: TitleDirectory,
    private val ingestion: AvailabilityIngestionService,
    private val properties: SnapshotJobProperties,
    private val tmdb: TmdbProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${plotted.availability.snapshot.cron}")
    fun run(): Report = refreshDueTitles()

    fun refreshDueTitles(): Report {
        if (!tmdb.isConfigured) {
            log.warn("Skipping availability snapshot: TMDB is not configured")
            return Report(0, 0, 0, 0)
        }

        val due = titles.findDueForAvailabilityRefresh(tmdb.region, properties.batchSize)
        if (due.isEmpty()) {
            log.info("Availability snapshot: nothing due")
            return Report(0, 0, 0, 0)
        }

        var refreshed = 0
        var changed = 0
        var unavailable = 0

        due.forEach { title ->
            val tmdbId = title.externalId.toIntOrNull()
            if (tmdbId == null) {
                log.warn("Skipping title {}: non-numeric TMDB id '{}'", title.titleId, title.externalId)
                return@forEach
            }
            val mediaType = if (title.mediaType == "movie") TmdbMediaType.MOVIE else TmdbMediaType.TV

            // Deliberately not wrapped in one transaction. A single bad title
            // must not roll back a night of snapshots for every other title.
            when (val outcome = runCatching { ingestion.refresh(title.titleId, mediaType, tmdbId) }.getOrNull()) {
                is AvailabilityIngestionService.RefreshOutcome.Refreshed -> {
                    refreshed++
                    if (outcome.diff.hasChanges) changed++
                }

                is AvailabilityIngestionService.RefreshOutcome.Unavailable -> unavailable++
                null -> unavailable++
            }
        }

        val report = Report(due.size, refreshed, changed, unavailable)
        log.info("Availability snapshot complete: {}", report.summary())
        return report
    }

    data class Report(
        val considered: Int,
        val refreshed: Int,
        val changed: Int,
        val unavailable: Int,
    ) {
        fun summary(): String = "$considered due, $refreshed refreshed, $changed with changes, $unavailable unreachable"
    }
}

@ConfigurationProperties(prefix = "plotted.availability.snapshot")
data class SnapshotJobProperties(
    /**
     * Off unless asked for. A developer running the application should not start
     * spending the TMDB quota without deciding to.
     */
    val enabled: Boolean = false,
    val cron: String = "0 30 3 * * *",
    /**
     * Titles per run. At 20 requests per second this is a few minutes of work,
     * and it is the knob to turn when the catalogue outgrows one nightly pass.
     */
    val batchSize: Int = 500,
)
