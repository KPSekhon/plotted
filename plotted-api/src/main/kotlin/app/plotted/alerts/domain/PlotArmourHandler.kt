package app.plotted.alerts.domain

import app.plotted.alerts.persistence.AlertRepository
import app.plotted.platform.outbox.OutboxEventTypes
import app.plotted.platform.outbox.OutboxHandler
import app.plotted.platform.outbox.OutboxRecord
import app.plotted.platform.spi.AvailabilityDirectory
import app.plotted.platform.spi.SubscriptionDirectory
import app.plotted.platform.spi.TitleDirectory
import app.plotted.platform.spi.WatchlistDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Plot Armour: turns a detected removal into the alerts worth sending.
 *
 * Fans one event out to everybody with the title on a list, asks [PlotArmour]
 * about each of them, and writes an alert only for those where the answer is
 * yes. In practice that is a small fraction — most people watching a title do
 * not pay for the service it left, and the ones who do have often already
 * watched it.
 *
 * **Every suppression is counted and logged.** A nightly job that says nothing
 * and a nightly job that is broken look identical from outside, and this
 * codebase has found six mechanisms that reported success while doing nothing.
 * The counts are what tell the two apart.
 *
 * Idempotent, as [OutboxHandler] requires: a redelivered event finds the alerts
 * it already wrote and suppresses them as `ALREADY_ALERTED`.
 */
@Component
class PlotArmourHandler(
    private val watchlists: WatchlistDirectory,
    private val subscriptions: SubscriptionDirectory,
    private val availability: AvailabilityDirectory,
    private val titles: TitleDirectory,
    private val alerts: AlertRepository,
    private val clock: Clock,
) : OutboxHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val eventType: String = OutboxEventTypes.AVAILABILITY_REMOVED

    override fun handle(record: OutboxRecord) {
        val titleId = UUID.fromString(record.payload["titleId"] as String)
        val leavingProviderId = UUID.fromString(record.payload["providerId"] as String)
        val regionCode = record.payload["regionCode"] as String
        val confidence = (record.payload["confidence"] as Number).toDouble()

        val watchers = watchlists.watchersOf(titleId)
        if (watchers.isEmpty()) {
            // Nobody has it listed. The overwhelmingly common case, and it costs
            // one query rather than the four the decision would otherwise need.
            log.debug("Title {} left provider {}: nobody is waiting on it", titleId, leavingProviderId)
            return
        }

        val titleName = titles.findSummaries(listOf(titleId)).singleOrNull()?.name
        // The title has gone from the catalogue between detection and delivery.
        // Nothing sensible to say about a film we can no longer name, and
        // inventing a placeholder would put "null has left Crave" in front of
        // somebody.
        if (titleName == null) {
            log.info("Title {} left provider {} but is no longer in the catalogue; nothing to say", titleId, leavingProviderId)
            return
        }

        val remaining = availability.subscriptionCoverage(listOf(titleId), regionCode)
            .byTitle[titleId]
            .orEmpty()
            .mapTo(mutableSetOf()) { it.providerId }

        val suppressed = mutableMapOf<PlotArmour.Suppression, Int>()
        var sent = 0

        watchers.forEach { watcher ->
            val held = subscriptions.currentSubscriptions(watcher.userId, LocalDate.now(clock))
            val leaving = held.firstOrNull { it.providerId == leavingProviderId }

            val context = PlotArmour.AlertContext(
                userId = watcher.userId,
                titleId = titleId,
                leavingProviderId = leavingProviderId,
                priority = watcher.priority,
                // watchersOf already returns outstanding, unblocked rows only, so
                // these two are settled by the query. Passed explicitly anyway so
                // the rules object stays a total function of its input rather
                // than one that quietly depends on how it was called.
                isOutstanding = true,
                isBlocked = false,
                subscribedProviderIds = held.mapTo(mutableSetOf()) { it.providerId },
                remainingProviderIds = remaining,
                confidence = confidence,
                alreadyAlerted = alerts.hasRecentAlert(
                    userId = watcher.userId,
                    titleId = titleId,
                    alertType = AlertTypes.AVAILABILITY_LEFT,
                    within = REPEAT_WINDOW,
                ),
            )

            when (val decision = PlotArmour.decide(context)) {
                is PlotArmour.Decision.Suppress -> suppressed.merge(decision.reason, 1, Int::plus)
                is PlotArmour.Decision.Send -> {
                    alerts.create(
                        Alert(
                            userId = watcher.userId,
                            alertType = AlertTypes.AVAILABILITY_LEFT,
                            severity = decision.severity.dbValue,
                            titleId = titleId,
                            message = messageFor(titleName, leaving?.providerName),
                            actionPayload = mapOf(
                                "titleId" to titleId.toString(),
                                "providerId" to leavingProviderId.toString(),
                                "regionCode" to regionCode,
                            ),
                        ),
                    )
                    sent++
                }
            }
        }

        log.info(
            "Plot Armour for title {} leaving provider {}: {} of {} watchers alerted, suppressed {}",
            titleId,
            leavingProviderId,
            sent,
            watchers.size,
            suppressed.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" },
        )
    }

    /**
     * What to actually say.
     *
     * Past tense, and no advice attached. The change detected is a departure that
     * has already happened, and dressing it as a warning — "watch it before it
     * goes" — would be describing something Plotted cannot see. Predicting a
     * departure needs the removal-risk model and the history it trains on.
     *
     * The provider name falls back to a generic phrasing rather than a raw id.
     * It is only ever null if the subscription vanished between the decision and
     * this line, which cannot happen inside one call — but a message template
     * that can print a UUID at somebody eventually will.
     */
    private fun messageFor(titleName: String, providerName: String?): String = if (providerName == null) {
        "$titleName has left one of the services you pay for."
    } else {
        "$titleName has left $providerName."
    }

    private companion object {
        /**
         * How long one alert silences the next about the same title.
         *
         * Long, on purpose. A title that flickers off a service and back — which
         * happens, and shows up in the feed as a removal followed by an addition
         * — must not produce a notification each time it wobbles. Dismissed
         * alerts count too, because dismissing is an answer.
         */
        val REPEAT_WINDOW: Duration = Duration.ofDays(60)
    }
}
