package app.plotted.subscriptions.domain

import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.subscriptions.persistence.SubscriptionRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SubscriptionServiceTest {
    private val today = LocalDate.of(2026, 8, 5)
    private val clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
    private val repository = mockk<SubscriptionRepository>(relaxed = true)
    private val service = SubscriptionService(repository, tmdbProperties(), clock)

    private val userId = UUID.randomUUID()
    private val providerId = UUID.randomUUID()

    @Test
    fun `the monthly total counts only what is currently being paid for`() {
        every { repository.findForUser(userId) } returns listOf(
            subscription("Netflix", BigDecimal("18.99"), SubscriptionStatus.ACTIVE),
            subscription("Crave", BigDecimal("11.00"), SubscriptionStatus.ACTIVE),
            // Kept for the record, but not costing anything now.
            subscription("Disney+", BigDecimal("11.99"), SubscriptionStatus.CANCELLED),
            subscription("Paramount+", BigDecimal("9.99"), SubscriptionStatus.LAPSED),
        )

        val summary = service.list(userId)

        summary.monthlyTotal shouldBe BigDecimal("29.99")
        summary.countedSubscriptions shouldBe 2
        // The cancelled rows are still returned: what someone used to pay for is
        // part of the record, it simply is not part of the bill.
        summary.subscriptions.size shouldBe 4
    }

    @Test
    fun `a trial counts towards the total because it is about to start costing money`() {
        every { repository.findForUser(userId) } returns listOf(
            subscription("Netflix", BigDecimal("18.99"), SubscriptionStatus.ACTIVE),
            subscription("Apple TV+", BigDecimal("12.99"), SubscriptionStatus.TRIAL),
        )

        service.list(userId).countedSubscriptions shouldBe 2
    }

    @Test
    fun `an annual plan is divided down rather than compared against monthly ones as-is`() {
        every { repository.findForUser(userId) } returns listOf(
            subscription("Amazon Prime", BigDecimal("99.00"), SubscriptionStatus.ACTIVE, BillingPeriod.ANNUAL),
        )

        // 99.00 / 12, not 99.00. A screen that mixes billing cycles is how
        // someone concludes their annual plan costs twelve times what it does.
        service.list(userId).monthlyTotal shouldBe BigDecimal("8.25")
    }

    @Test
    fun `a commitment end date in the future makes the subscription uncancellable on its own`() {
        every { repository.providerExists(providerId) } returns true
        every { repository.findOrCreatePlan(any(), any(), any(), any(), any(), any()) } returns UUID.randomUUID()
        val cannotCancel = slot<Boolean>()
        every {
            repository.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cannotCancel), any())
        } returns UUID.randomUUID()
        every { repository.findOne(any(), any()) } returns subscription("Crave", BigDecimal("11.00"), SubscriptionStatus.ACTIVE)

        service.add(userId, newSubscription(cannotCancel = false, commitmentEndsOn = today.plusMonths(6)))

        // Derived rather than trusted from the request: the caller said false,
        // but a commitment running six more months says otherwise, and phase 5
        // must never advise cancelling something that cannot be cancelled.
        cannotCancel.captured shouldBe true
    }

    @Test
    fun `a commitment that has already ended does not keep the subscription locked`() {
        every { repository.providerExists(providerId) } returns true
        every { repository.findOrCreatePlan(any(), any(), any(), any(), any(), any()) } returns UUID.randomUUID()
        val cannotCancel = slot<Boolean>()
        every {
            repository.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(cannotCancel), any())
        } returns UUID.randomUUID()
        every { repository.findOne(any(), any()) } returns subscription("Crave", BigDecimal("11.00"), SubscriptionStatus.ACTIVE)

        service.add(userId, newSubscription(cannotCancel = false, commitmentEndsOn = today.minusDays(1)))

        cannotCancel.captured shouldBe false
    }

    @Test
    fun `the plan is recorded with the price the user gave, not one Plotted invented`() {
        every { repository.providerExists(providerId) } returns true
        val price = slot<BigDecimal>()
        every { repository.findOrCreatePlan(any(), any(), any(), any(), capture(price), any()) } returns UUID.randomUUID()
        every { repository.findOne(any(), any()) } returns subscription("Crave", BigDecimal("11.00"), SubscriptionStatus.ACTIVE)

        service.add(userId, newSubscription(price = BigDecimal("14.49")))

        price.captured shouldBe BigDecimal("14.49")
        verify { repository.findOrCreatePlan(providerId, "CA", "Standard with ads", BillingPeriod.MONTHLY, BigDecimal("14.49"), "CAD") }
    }

    // --- helpers -----------------------------------------------------------

    private fun newSubscription(
        price: BigDecimal = BigDecimal("11.00"),
        cannotCancel: Boolean = false,
        commitmentEndsOn: LocalDate? = null,
    ) = SubscriptionService.NewSubscription(
        providerId = providerId,
        planName = "Standard with ads",
        billingPeriod = BillingPeriod.MONTHLY,
        price = price,
        currency = "CAD",
        status = SubscriptionStatus.ACTIVE,
        startedOn = today,
        renewsOn = null,
        commitmentEndsOn = commitmentEndsOn,
        autoRenews = true,
        cannotCancel = cannotCancel,
        notes = null,
    )

    private fun subscription(
        name: String,
        price: BigDecimal,
        status: SubscriptionStatus,
        billingPeriod: BillingPeriod = BillingPeriod.MONTHLY,
    ) = Subscription(
        id = UUID.randomUUID(),
        providerId = UUID.randomUUID(),
        providerName = name,
        providerSlug = name.lowercase(),
        providerLogoUrl = null,
        planName = "Standard",
        billingPeriod = billingPeriod,
        price = price,
        currency = "CAD",
        status = status,
        startedOn = today.minusMonths(2),
        renewsOn = today.plusDays(10),
        autoRenews = true,
        cannotCancel = false,
        commitmentEndsOn = null,
        notes = null,
    )

    private fun tmdbProperties(): TmdbProperties = mockk<TmdbProperties>().also {
        every { it.region } returns "CA"
    }

    private companion object {
        @Suppress("unused")
        val EPOCH: Instant = Instant.EPOCH
    }
}
