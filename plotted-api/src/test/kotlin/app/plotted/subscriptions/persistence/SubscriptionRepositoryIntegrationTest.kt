package app.plotted.subscriptions.persistence

import app.plotted.availability.persistence.ProviderRepository
import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.subscriptions.domain.BillingPeriod
import app.plotted.subscriptions.domain.SubscriptionStatus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Subscription persistence against a real database.
 *
 * The interesting part is [SubscriptionRepository.findOrCreatePlan]. It writes to
 * `provider_plans`, which carries a GiST exclusion constraint on
 * (provider, region, name, validity) that the jOOQ generator cannot see -- so the
 * `upper_inf(validity)` lookup and the `daterange(today, NULL)` insert are both
 * hand-written SQL that nothing else in the build type-checks.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class SubscriptionRepositoryIntegrationTest {
    @Autowired
    private lateinit var repository: SubscriptionRepository

    @Autowired
    private lateinit var providers: ProviderRepository

    @Autowired
    private lateinit var dsl: DSLContext

    private val netflix get() = providers.findBySlug("netflix")!!
    private val crave get() = providers.findBySlug("crave")!!

    @Test
    fun `a plan is created once and found thereafter`() {
        val first = repository.findOrCreatePlan(netflix.id, "CA", planName(), BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")
        val again = repository.findOrCreatePlan(netflix.id, "CA", planNameOf(first), BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")

        // The second call must find the current row rather than inserting a
        // second one -- which the exclusion constraint would reject outright,
        // turning a routine second subscription into a 500.
        again shouldBe first
    }

    @Test
    fun `two plans for the same provider coexist when they are different plans`() {
        val standard = repository.findOrCreatePlan(netflix.id, "CA", planName(), BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")
        val withAds = repository.findOrCreatePlan(netflix.id, "CA", planName(), BillingPeriod.MONTHLY, BigDecimal("5.99"), "CAD")

        // The constraint keys on plan name as well, so tiers do not collide.
        (standard == withAds) shouldBe false
    }

    @Test
    fun `the same plan name on a different provider is a separate row`() {
        val name = planName()
        val onNetflix = repository.findOrCreatePlan(netflix.id, "CA", name, BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")
        val onCrave = repository.findOrCreatePlan(crave.id, "CA", name, BillingPeriod.MONTHLY, BigDecimal("11.00"), "CAD")

        (onNetflix == onCrave) shouldBe false
    }

    @Test
    fun `the created plan is open-ended so it reads as current`() {
        val id = repository.findOrCreatePlan(netflix.id, "CA", planName(), BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")

        // [today, ) -- unbounded above means "this is the price now". A bounded
        // range here would make findOrCreatePlan miss it next time and then
        // collide with it on insert.
        validityOf(id) shouldBe "[${LocalDate.now()},)"
    }

    @Test
    fun `a subscription reads back with its provider and plan joined in`() {
        val userId = givenUser()
        val planId = repository.findOrCreatePlan(crave.id, "CA", "Standard", BillingPeriod.MONTHLY, BigDecimal("11.00"), "CAD")

        val id = repository.insert(
            userId = userId,
            providerPlanId = planId,
            price = BigDecimal("9.99"),
            currency = "CAD",
            status = SubscriptionStatus.ACTIVE,
            startedOn = LocalDate.of(2026, 1, 1),
            renewsOn = LocalDate.of(2026, 9, 1),
            commitmentEndsOn = null,
            autoRenews = true,
            cannotCancel = false,
            notes = null,
        )

        val stored = repository.findOne(userId, id).shouldNotBeNull()
        stored.providerName shouldBe crave.name
        stored.planName shouldBe "Standard"
        // The user's own figure wins over the plan's list price: a grandfathered
        // rate is what they actually pay, and that is what phase 5 minimises.
        stored.price shouldBe BigDecimal("9.99")
        stored.billingPeriod shouldBe BillingPeriod.MONTHLY
        stored.renewsOn shouldBe LocalDate.of(2026, 9, 1)
    }

    @Test
    fun `one user cannot read or change another user's subscription`() {
        val owner = givenUser()
        val stranger = givenUser()
        val planId = repository.findOrCreatePlan(netflix.id, "CA", planName(), BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")
        val id = repository.insert(
            owner, planId, BigDecimal("18.99"), "CAD", SubscriptionStatus.ACTIVE,
            LocalDate.of(2026, 1, 1), null, null, true, false, null,
        )

        repository.findOne(stranger, id).shouldBeNull()
        repository.update(stranger, id, SubscriptionStatus.CANCELLED, null, false, null, null, null, false) shouldBe false
        repository.delete(stranger, id) shouldBe false
        repository.findOne(owner, id).shouldNotBeNull()
    }

    @Test
    fun `an empty patch reports existence rather than issuing invalid SQL`() {
        val userId = givenUser()
        val planId = repository.findOrCreatePlan(netflix.id, "CA", planName(), BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")
        val id = repository.insert(
            userId, planId, BigDecimal("18.99"), "CAD", SubscriptionStatus.ACTIVE,
            LocalDate.of(2026, 1, 1), null, null, true, false, null,
        )

        repository.update(userId, id, null, null, false, null, null, null, false) shouldBe true
        repository.update(userId, UUID.randomUUID(), null, null, false, null, null, null, false) shouldBe false
    }

    @Test
    fun `clearing the renewal date differs from leaving it alone`() {
        val userId = givenUser()
        val planId = repository.findOrCreatePlan(netflix.id, "CA", planName(), BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")
        val id = repository.insert(
            userId, planId, BigDecimal("18.99"), "CAD", SubscriptionStatus.ACTIVE,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1), null, true, false, "keep me",
        )

        repository.update(userId, id, null, null, true, null, null, null, false) shouldBe true

        val updated = repository.findOne(userId, id).shouldNotBeNull()
        updated.renewsOn.shouldBeNull()
        updated.notes shouldBe "keep me"
    }

    @Test
    fun `providerExists rejects an id that is not a provider`() {
        repository.providerExists(netflix.id) shouldBe true
        repository.providerExists(UUID.randomUUID()) shouldBe false
    }

    @Test
    fun `every status round trips through the check constraint`() {
        val userId = givenUser()
        val planId = repository.findOrCreatePlan(netflix.id, "CA", planName(), BillingPeriod.MONTHLY, BigDecimal("18.99"), "CAD")
        val id = repository.insert(
            userId, planId, BigDecimal("18.99"), "CAD", SubscriptionStatus.ACTIVE,
            LocalDate.of(2026, 1, 1), null, null, true, false, null,
        )

        SubscriptionStatus.entries.forEach { status ->
            repository.update(userId, id, status, null, false, null, null, null, false) shouldBe true
            repository.findOne(userId, id)!!.status shouldBe status
        }
    }

    @Test
    fun `every billing period round trips`() {
        BillingPeriod.entries.forEach { period ->
            val planId = repository.findOrCreatePlan(crave.id, "CA", planName(), period, BigDecimal("11.00"), "CAD")
            val userId = givenUser()
            val id = repository.insert(
                userId, planId, BigDecimal("11.00"), "CAD", SubscriptionStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), null, null, true, false, null,
            )
            repository.findOne(userId, id)!!.billingPeriod shouldBe period
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun givenUser(): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, "subscription-${SEQUENCE.incrementAndGet()}@example.test")
            .set(USERS.DISPLAY_NAME, "Test")
            .execute()
        return id
    }

    /** A unique plan name, so tests cannot collide through the exclusion constraint. */
    private fun planName(): String = "Plan ${SEQUENCE.incrementAndGet()}"

    private fun planNameOf(planId: UUID): String = dsl
        .select(app.plotted.generated.jooq.tables.references.PROVIDER_PLANS.NAME)
        .from(app.plotted.generated.jooq.tables.references.PROVIDER_PLANS)
        .where(app.plotted.generated.jooq.tables.references.PROVIDER_PLANS.ID.eq(planId))
        .fetchOne()!!
        .value1()!!

    private fun validityOf(planId: UUID): String? = dsl
        .select(DSL.field("validity::text", String::class.java))
        .from(DSL.table("provider_plans"))
        .where(DSL.field("id", UUID::class.java).eq(planId))
        .fetchOne()
        ?.value1()

    companion object {
        private val SEQUENCE = AtomicInteger(4_000_000)

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("plotted")
                .withUsername("plotted")
                .withPassword("plotted")
    }
}
