package app.plotted.solver

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The independent checker, checked.
 *
 * A second implementation is only worth having if it genuinely disagrees when
 * the first one is wrong, so these tests hand it plans that violate each rule
 * and assert it notices. A checker that passes everything is decoration.
 */
class PlanCheckerTest {
    private val netflix = UUID.randomUUID()
    private val crave = UUID.randomUUID()
    private val disney = UUID.randomUUID()

    @Test
    fun `a sound plan produces no complaints`() {
        val request = request(maximumMonthlyCents = 4_000)
        val plan = listOf(
            month(0, setOf(netflix), started = setOf(netflix), cents = 1_899),
            month(1, setOf(netflix), cents = 1_899),
        )

        PlanChecker.check(request, plan) shouldBe emptyList()
    }

    @Test
    fun `it catches a month that exceeds the budget`() {
        val request = request(maximumMonthlyCents = 2_000)
        val plan = listOf(
            month(0, setOf(netflix, crave), started = setOf(netflix, crave), cents = 3_099),
            month(1, setOf(netflix, crave), cents = 3_099),
        )

        val problems = PlanChecker.check(request, plan)
        (problems.any { it.contains("over the") }) shouldBe true
    }

    @Test
    fun `it catches a reported cost that does not match the services held`() {
        val request = request()
        // The kind of error a model with a wrong coefficient produces: a plan
        // that looks internally consistent and is not.
        val plan = listOf(month(0, setOf(netflix), started = setOf(netflix), cents = 1L), month(1, setOf(netflix), cents = 1_899))

        (PlanChecker.check(request, plan).any { it.contains("but its services cost") }) shouldBe true
    }

    @Test
    fun `it catches a cancellation inside a commitment`() {
        val request = request(craveCommittedMonths = 2)
        val plan = listOf(
            month(0, setOf(crave), cents = 1_200),
            // Dropped in month 1 while still committed through month 2.
            month(1, emptySet(), stopped = setOf(crave), cents = 0),
        )

        val problems = PlanChecker.check(request, plan)
        (problems.any { it.contains("cannot be cancelled") }) shouldBe true
    }

    @Test
    fun `it catches starts and stops that disagree with the subscription state`() {
        val request = request()
        val plan = listOf(
            // Claims to start nothing while picking up Netflix. This is exactly
            // what a broken switching linearisation produces: a valid-looking
            // plan that under-counts churn and so under-prices it.
            month(0, setOf(netflix), started = emptySet(), cents = 1_899),
            month(1, setOf(netflix), cents = 1_899),
        )

        (PlanChecker.check(request, plan).any { it.contains("reports starting") }) shouldBe true
    }

    @Test
    fun `it counts an already-held service as continuing rather than starting`() {
        val request = request(netflixHeld = true)
        val plan = listOf(
            month(0, setOf(netflix), started = emptySet(), cents = 1_899),
            month(1, setOf(netflix), cents = 1_899),
        )

        // Charging a switch for something the user already had would make every
        // plan that keeps the status quo look like churn.
        PlanChecker.check(request, plan) shouldBe emptyList()
    }

    @Test
    fun `a title is counted once, in the first month it becomes reachable`() {
        val request = request()
        val plan = listOf(
            month(0, setOf(netflix), started = setOf(netflix), cents = 1_899),
            month(1, setOf(netflix), cents = 1_899),
        )

        val covered = PlanChecker.coverage(request, plan)
        covered.size shouldBe 1
        covered.single().month shouldBe 0
    }

    @Test
    fun `coverage is priority-weighted, not a count`() {
        val urgent = TitleDemand(UUID.randomUUID(), "Urgent", priorityPoints = 5, availableOn = setOf(netflix))
        val minor = TitleDemand(UUID.randomUUID(), "Minor", priorityPoints = 1, availableOn = setOf(disney))
        val request = request().copy(titles = listOf(urgent, minor))

        val netflixOnly = listOf(month(0, setOf(netflix), started = setOf(netflix), cents = 1_899))
        val objective = PlanChecker.objective(request, netflixOnly, PlanChecker.coverage(request, netflixOnly))

        // 5 of 6 points, not "one of two titles". Same reasoning as the coverage
        // dashboard: an unweighted count says the two plans are equal.
        objective.coverage shouldBe (5.0 / 6.0 plusOrMinus 1e-9)
    }

    @Test
    fun `every objective term lands between zero and one`() {
        val request = request()
        val plan = listOf(
            month(0, setOf(netflix, crave), started = setOf(netflix, crave), cents = 3_099),
            month(1, setOf(netflix), stopped = setOf(crave), cents = 1_899),
        )

        val objective = PlanChecker.objective(request, plan, PlanChecker.coverage(request, plan))

        // This is what makes the weights mean anything. A term outside [0,1]
        // would let one objective outvote the others for reasons of scale.
        listOf(objective.coverage, objective.costFraction, objective.switchFraction).forEach {
            (it in 0.0..1.0) shouldBe true
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun request(maximumMonthlyCents: Long? = null, craveCommittedMonths: Int = 0, netflixHeld: Boolean = false) = PlanRequest(
        services = listOf(
            ServiceOption(netflix, "Netflix", 1_899, committedMonths = 0, currentlySubscribed = netflixHeld),
            ServiceOption(crave, "Crave", 1_200, committedMonths = craveCommittedMonths, currentlySubscribed = craveCommittedMonths > 0),
            ServiceOption(disney, "Disney+", 1_599, committedMonths = 0, currentlySubscribed = false),
        ),
        titles = listOf(TitleDemand(UUID.randomUUID(), "A Film", priorityPoints = 3, availableOn = setOf(netflix))),
        constraints = PlanConstraints(
            horizonMonths = 2,
            maximumMonthlyCents = maximumMonthlyCents,
            maximumActiveServices = null,
            maximumMonthlySwitches = null,
        ),
        weights = PlanWeights.DEFAULT,
    )

    private fun month(month: Int, held: Set<UUID>, started: Set<UUID> = emptySet(), stopped: Set<UUID> = emptySet(), cents: Long) =
        MonthPlan(month, held, started, stopped, cents)
}
