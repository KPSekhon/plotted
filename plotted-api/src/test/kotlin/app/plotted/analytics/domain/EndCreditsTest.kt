package app.plotted.analytics.domain

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The two metrics, and the ways they could flatter.
 *
 * Both are arithmetic, which is exactly why they are worth testing: the failure
 * mode is not an exception, it is a number that looks plausible and is kind to
 * itself. Each case here is one specific way that could happen.
 */
class EndCreditsTest {
    @Test
    fun `the median ignores an outlier that would drag a mean`() {
        val latencies = listOf(20L, 25L, 30L, 35L, 3_600L).map(Duration::ofSeconds)

        // The mean here is over twelve minutes and the median is thirty seconds.
        // Wall-clock has an unbounded tail -- somebody leaves the tab open -- and
        // one such observation would otherwise define the headline number.
        DecisionLatency.of(latencies, excludedAsStale = 0).median shouldBe Duration.ofSeconds(30)
    }

    @Test
    fun `an even sample takes the mean of the two middle values`() {
        val latencies = listOf(10L, 20L, 30L, 40L).map(Duration::ofSeconds)

        // Written out rather than taken from a library, because this is the case
        // implementations disagree on -- and two reports both called "the median"
        // that quietly differ is worse than one that is arguably wrong.
        DecisionLatency.of(latencies, excludedAsStale = 0).median shouldBe Duration.ofSeconds(25)
    }

    @Test
    fun `no acceptances means no latency, not a latency of zero`() {
        val latency = DecisionLatency.of(emptyList(), excludedAsStale = 0)

        // Zero is the best possible number, and it would be arrived at by having
        // no evidence whatsoever.
        latency.median.shouldBeNull()
        latency.sampleSize shouldBe 0
    }

    @Test
    fun `stale acceptances are reported rather than dropped`() {
        val latency = DecisionLatency.of(listOf(Duration.ofSeconds(30)), excludedAsStale = 7)

        // Excluded from the statistic and visible beside it. A screen showing a
        // 30-second median built from one observation, with seven thrown away
        // unmentioned, is worse than one that says so.
        latency.sampleSize shouldBe 1
        latency.excludedAsStale shouldBe 7
    }

    @Test
    fun `the completion rate divides by what could actually have been finished`() {
        val rate = CompletionRate.of(completed = 3, judged = 4, tooRecentToJudge = 6)

        // The six accepted last week are not failures. Including them would put
        // the rate at 0.3 and let it climb on its own as the log aged, which
        // looks exactly like the product getting better.
        rate.rate!! shouldBe (0.75 plusOrMinus 1e-9)
        rate.tooRecentToJudge shouldBe 6
    }

    @Test
    fun `nothing mature enough to judge means no rate at all`() {
        val rate = CompletionRate.of(completed = 0, judged = 0, tooRecentToJudge = 12)

        // Null rather than 0.0. "Nobody finished anything" and "nothing has had
        // the chance to be finished" are opposite findings that a zero would
        // render identically.
        rate.rate.shouldBeNull()
        rate.tooRecentToJudge shouldBe 12
    }

    @Test
    fun `a genuine zero is still a zero`() {
        // The converse matters too: with a real denominator, nobody finishing
        // anything is a finding and must not be softened into null.
        CompletionRate.of(completed = 0, judged = 9, tooRecentToJudge = 0).rate!! shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `the windows are stated rather than implied`() {
        // Both are judgements, and the point of naming them is that they can be
        // argued with. This asserts they exist and are the documented values, so
        // a silent change to either shows up as a failing test rather than as a
        // metric that moved for no visible reason.
        DecisionLatency.WINDOW shouldBe Duration.ofHours(4)
        CompletionRate.MATURITY shouldBe Duration.ofDays(14)
    }
}
