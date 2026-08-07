package app.plotted.alerts.domain

import java.util.UUID

/**
 * Whether one person should be told that one title is leaving one service.
 *
 * This is the whole of Plot Armour that is worth arguing about. Detecting a
 * removal is a diff; deciding whether to mention it is the product.
 *
 * A job that fires nightly is a job the user turns off, and a notification
 * feature nobody has enabled is worth less than none at all — so the design
 * question is not "what can we detect" but "what is worth saying". Every rule
 * below removes alerts, and each one exists because sending that alert would
 * spend credibility the next one needs.
 *
 * Written as a pure function over a decided [AlertContext] so the rules can be
 * tested exhaustively without a database, a clock or a title that happens to be
 * leaving Crave this week.
 */
object PlotArmour {
    /**
     * Why an alert was not sent.
     *
     * Reported rather than discarded. A suppression counter is the only way to
     * tell "Plot Armour is working and there is nothing to say" from "Plot Armour
     * is broken and says nothing" — and those look identical from outside, which
     * is the failure mode this codebase keeps finding.
     */
    enum class Suppression {
        /** They never put it on a list. Everything downstream assumes intent. */
        NOT_ON_ANY_LIST,

        /** Already watched, or given up on. Leaving is not their problem any more. */
        NOT_OUTSTANDING,

        /** They asked never to see it. A removal notice is still showing it to them. */
        BLOCKED,

        /**
         * They do not pay for the service it is leaving.
         *
         * The single most important rule here. A title leaving Paramount+ is not
         * news to somebody who has never had Paramount+ — it changes nothing they
         * could act on, and it is the alert that makes the whole feature feel
         * like spam.
         */
        NOT_SUBSCRIBED,

        /**
         * Still watchable on something else they pay for.
         *
         * Nothing is lost, so there is nothing to act on. Worth suppressing
         * rather than downgrading: "this left Netflix but it is on Crave, which
         * you have" is a sentence about Plotted's bookkeeping, not about them.
         */
        STILL_COVERED,

        /**
         * The observation was made from a partial picture.
         *
         * A pass where some providers could not be mapped sees fewer offers than
         * exist, so a "removal" from it may be a gap rather than a departure.
         * Telling somebody a film has left when it has not is the one error that
         * costs the feature its credibility outright, and the next pass will say
         * so properly if it is real.
         */
        LOW_CONFIDENCE,

        /** Already told them about this title leaving this service. */
        ALREADY_ALERTED,
    }

    sealed interface Decision {
        data class Send(val severity: Severity) : Decision

        data class Suppress(val reason: Suppression) : Decision
    }

    enum class Severity(val dbValue: String) {
        INFO("info"),
        WARNING("warning"),
        URGENT("urgent"),
    }

    /**
     * Everything the decision needs, already gathered.
     *
     * A struct rather than four collaborators, so the rules are a function of
     * their inputs and the test does not need a Spring context to ask "what about
     * a blocked title on a service they do not have".
     */
    data class AlertContext(
        val userId: UUID,
        val titleId: UUID,
        val leavingProviderId: UUID,
        /** Null when the title is not on this user's list at all. */
        val priority: Int?,
        val isOutstanding: Boolean,
        val isBlocked: Boolean,
        val subscribedProviderIds: Set<UUID>,
        /** Providers still carrying it on a subscription, after the removal. */
        val remainingProviderIds: Set<UUID>,
        val confidence: Double,
        val alreadyAlerted: Boolean,
    )

    /**
     * Ordered deliberately. The cheapest and most absolute checks come first, and
     * the reported reason is the *first* thing that would have stopped the alert
     * rather than an arbitrary one of several — so the suppression counts read as
     * a funnel instead of a tally that double-counts.
     */
    fun decide(context: AlertContext): Decision {
        if (context.priority == null) return Decision.Suppress(Suppression.NOT_ON_ANY_LIST)
        if (!context.isOutstanding) return Decision.Suppress(Suppression.NOT_OUTSTANDING)
        if (context.isBlocked) return Decision.Suppress(Suppression.BLOCKED)
        if (context.leavingProviderId !in context.subscribedProviderIds) {
            return Decision.Suppress(Suppression.NOT_SUBSCRIBED)
        }
        if (context.confidence < MINIMUM_CONFIDENCE) return Decision.Suppress(Suppression.LOW_CONFIDENCE)
        if (context.remainingProviderIds.any { it in context.subscribedProviderIds }) {
            return Decision.Suppress(Suppression.STILL_COVERED)
        }
        if (context.alreadyAlerted) return Decision.Suppress(Suppression.ALREADY_ALERTED)

        return Decision.Send(severityFor(context))
    }

    /**
     * How loudly to say it, from how much the person said they wanted it.
     *
     * Priority is the only signal available that came from the user rather than
     * from the feed, and it is exactly the question being asked: how much does
     * losing this matter to you. Anything derived from popularity would be
     * answering how much it matters to everyone else.
     */
    private fun severityFor(context: AlertContext): Severity = when {
        context.priority!! <= URGENT_PRIORITY -> Severity.URGENT
        context.priority <= WARNING_PRIORITY -> Severity.WARNING
        else -> Severity.INFO
    }

    /**
     * Below this a removal is treated as possibly an artefact of a partial feed.
     *
     * Set just above the 0.800 the ingestion service applies when some providers
     * could not be mapped, so exactly that case is caught. It is a threshold on a
     * number the feed produces, not a probability anybody has measured, and it
     * should be revisited once there is enough history to know how often a
     * low-confidence removal turns out to be real.
     */
    const val MINIMUM_CONFIDENCE = 0.9

    /** 1 and 2 are "desperate to see" and "keen". */
    private const val URGENT_PRIORITY = 2

    /** 3 is "interested". Below that, a departure is worth knowing and not worth interrupting for. */
    private const val WARNING_PRIORITY = 3
}
