package app.plotted.alerts.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What Plot Armour decides not to say.
 *
 * Detecting a removal is a diff. Deciding whether it is worth mentioning is the
 * product, and it is the part that decides whether the feature survives contact
 * with a real user: a nightly job that sends one alert too many is a nightly job
 * somebody turns off, and then the useful alerts stop arriving too.
 *
 * So most of this file is about alerts that are *not* sent.
 */
class PlotArmourTest {
    private val crave = UUID.randomUUID()
    private val netflix = UUID.randomUUID()
    private val disney = UUID.randomUUID()

    @Test
    fun `a wanted title leaving a service you pay for is worth saying`() {
        PlotArmour.decide(context()) shouldBe PlotArmour.Decision.Send(PlotArmour.Severity.WARNING)
    }

    @Test
    fun `a title you never listed is not news`() {
        // Everything downstream assumes intent. Without a list entry there is no
        // evidence the person cares, and guessing from popularity would be
        // answering how much it matters to everybody else.
        PlotArmour.decide(context(priority = null)) shouldBe
            PlotArmour.Decision.Suppress(PlotArmour.Suppression.NOT_ON_ANY_LIST)
    }

    @Test
    fun `a title you have already watched is not news`() {
        PlotArmour.decide(context(isOutstanding = false)) shouldBe
            PlotArmour.Decision.Suppress(PlotArmour.Suppression.NOT_OUTSTANDING)
    }

    @Test
    fun `a title you blocked is not news`() {
        // Sending a removal notice for a blocked title is still putting it in
        // front of somebody who asked never to see it.
        PlotArmour.decide(context(isBlocked = true)) shouldBe
            PlotArmour.Decision.Suppress(PlotArmour.Suppression.BLOCKED)
    }

    @Test
    fun `a title leaving a service you do not pay for is not news`() {
        // The rule that matters most. Nothing about this changes anything the
        // person could act on, and it is the alert that would make the whole
        // feature feel like spam.
        PlotArmour.decide(context(subscribed = setOf(netflix))) shouldBe
            PlotArmour.Decision.Suppress(PlotArmour.Suppression.NOT_SUBSCRIBED)
    }

    @Test
    fun `a title still on another service you pay for is not news`() {
        // Nothing was lost. Suppressed rather than downgraded, because "this left
        // Crave but it is on Netflix, which you have" is a sentence about
        // Plotted's bookkeeping rather than about them.
        PlotArmour.decide(
            context(subscribed = setOf(crave, netflix), remaining = setOf(netflix)),
        ) shouldBe PlotArmour.Decision.Suppress(PlotArmour.Suppression.STILL_COVERED)
    }

    @Test
    fun `a title still on a service you do not pay for is still news`() {
        // It is elsewhere, but not anywhere they can watch it. Losing access is
        // the thing being reported, not the title vanishing from the world.
        PlotArmour.decide(
            context(subscribed = setOf(crave), remaining = setOf(disney)),
        ) shouldBe PlotArmour.Decision.Send(PlotArmour.Severity.WARNING)
    }

    @Test
    fun `a removal seen through a partial feed is not reported`() {
        // A pass where some providers could not be mapped sees fewer offers than
        // exist, so the "removal" may be a gap. Telling somebody a film has left
        // when it has not is the error that costs the feature its credibility,
        // and the next clean pass will report it properly if it is real.
        PlotArmour.decide(context(confidence = 0.8)) shouldBe
            PlotArmour.Decision.Suppress(PlotArmour.Suppression.LOW_CONFIDENCE)
    }

    @Test
    fun `the confidence threshold catches exactly the reduced-confidence pass`() {
        // The ingestion service stamps 0.800 when it could not map everything and
        // 1.000 otherwise. If these ever drift apart, every removal is either
        // always reported or never.
        PlotArmour.decide(context(confidence = 1.0)).let { it is PlotArmour.Decision.Send } shouldBe true
        PlotArmour.decide(context(confidence = 0.8)).let { it is PlotArmour.Decision.Suppress } shouldBe true
    }

    @Test
    fun `telling somebody twice is worse than not telling them`() {
        PlotArmour.decide(context(alreadyAlerted = true)) shouldBe
            PlotArmour.Decision.Suppress(PlotArmour.Suppression.ALREADY_ALERTED)
    }

    @Test
    fun `how much they wanted it decides how loudly to say it`() {
        // Priority is the only signal here that came from the user rather than
        // from a feed, and it is exactly the question being asked.
        PlotArmour.decide(context(priority = 1)) shouldBe PlotArmour.Decision.Send(PlotArmour.Severity.URGENT)
        PlotArmour.decide(context(priority = 2)) shouldBe PlotArmour.Decision.Send(PlotArmour.Severity.URGENT)
        PlotArmour.decide(context(priority = 3)) shouldBe PlotArmour.Decision.Send(PlotArmour.Severity.WARNING)
        PlotArmour.decide(context(priority = 4)) shouldBe PlotArmour.Decision.Send(PlotArmour.Severity.INFO)
        PlotArmour.decide(context(priority = 5)) shouldBe PlotArmour.Decision.Send(PlotArmour.Severity.INFO)
    }

    @Test
    fun `the reported reason is the first one that applied, not an arbitrary one`() {
        // Someone who blocked a title, on a service they do not hold, seen through
        // a partial feed. Three rules apply; the funnel reports the earliest, so
        // the suppression counts add up to the number of watchers instead of
        // double-counting the same person under three headings.
        PlotArmour.decide(
            context(isBlocked = true, subscribed = emptySet(), confidence = 0.1),
        ) shouldBe PlotArmour.Decision.Suppress(PlotArmour.Suppression.BLOCKED)
    }

    @Test
    fun `every suppression reason is reachable`() {
        // A reason nothing can produce is a rule that has been edited out of the
        // decision and left in the enum, and it would show up in the logs as a
        // counter that is always zero.
        val reached = listOf(
            context(priority = null),
            context(isOutstanding = false),
            context(isBlocked = true),
            context(subscribed = setOf(netflix)),
            context(confidence = 0.5),
            context(subscribed = setOf(crave, netflix), remaining = setOf(netflix)),
            context(alreadyAlerted = true),
        ).mapNotNull { (PlotArmour.decide(it) as? PlotArmour.Decision.Suppress)?.reason }

        reached.toSet() shouldBe PlotArmour.Suppression.entries.toSet()
    }

    private fun context(
        priority: Int? = 3,
        isOutstanding: Boolean = true,
        isBlocked: Boolean = false,
        subscribed: Set<UUID> = setOf(crave),
        remaining: Set<UUID> = emptySet(),
        confidence: Double = 1.0,
        alreadyAlerted: Boolean = false,
    ) = PlotArmour.AlertContext(
        userId = UUID.randomUUID(),
        titleId = UUID.randomUUID(),
        leavingProviderId = crave,
        priority = priority,
        isOutstanding = isOutstanding,
        isBlocked = isBlocked,
        subscribedProviderIds = subscribed,
        remainingProviderIds = remaining,
        confidence = confidence,
        alreadyAlerted = alreadyAlerted,
    )
}
