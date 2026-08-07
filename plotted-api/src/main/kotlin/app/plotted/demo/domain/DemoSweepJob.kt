package app.plotted.demo.domain

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Deletes demo accounts once they expire.
 *
 * Conditional on demo mode being enabled, so a deployment that never creates
 * demo accounts does not run an hourly delete against its users table. That is
 * not only tidiness: the sweep's `WHERE` clause is the only thing standing
 * between it and real accounts, and a job that never needs to run is a job that
 * should not be armed.
 *
 * Hourly rather than nightly because the account ceiling is what the endpoint
 * refuses against — a sweep that runs once a day means a busy afternoon can
 * close the demo until the following night.
 */
@Component
@ConditionalOnProperty(prefix = "plotted.demo", name = ["enabled"], havingValue = "true")
class DemoSweepJob(private val demo: DemoService) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${plotted.demo.sweep-cron:0 7 * * * *}")
    fun sweep() {
        val deleted = demo.sweepExpired()
        // Logged only when it did something. An hourly line saying "removed 0"
        // is noise that trains people to skip the log this job writes to.
        if (deleted > 0) {
            logger.info("Removed {} expired demo account(s)", deleted)
        }
    }
}
