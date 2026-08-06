package app.plotted.subscriptions.persistence

import app.plotted.generated.jooq.tables.references.PROVIDERS
import app.plotted.generated.jooq.tables.references.PROVIDER_PLANS
import app.plotted.generated.jooq.tables.references.USER_SUBSCRIPTIONS
import app.plotted.subscriptions.domain.BillingPeriod
import app.plotted.subscriptions.domain.Subscription
import app.plotted.subscriptions.domain.SubscriptionStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Subscription persistence.
 *
 * Like the watchlist repository, every item method is scoped by user id rather
 * than trusting a subscription id on its own, so one user cannot read or edit
 * another's row by guessing.
 *
 * The `providers` and `provider_plans` joins reach across a module boundary in
 * SQL. That is the same deliberate line the catalogue draws when it joins
 * `title_availability`: no *class* crosses a feature boundary, because that is
 * the coupling that spreads, but both tables live in one database and joining
 * them is what stops this screen from being N+1 queries.
 */
@Repository
class SubscriptionRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    fun findForUser(userId: UUID): List<Subscription> = dsl.select(
        USER_SUBSCRIPTIONS.ID,
        USER_SUBSCRIPTIONS.STATUS,
        USER_SUBSCRIPTIONS.STARTED_ON,
        USER_SUBSCRIPTIONS.RENEWS_ON,
        USER_SUBSCRIPTIONS.COMMITMENT_ENDS_ON,
        USER_SUBSCRIPTIONS.ACTUAL_PRICE,
        USER_SUBSCRIPTIONS.CURRENCY,
        USER_SUBSCRIPTIONS.AUTO_RENEWS,
        USER_SUBSCRIPTIONS.CANNOT_CANCEL,
        USER_SUBSCRIPTIONS.NOTES,
        PROVIDER_PLANS.NAME,
        PROVIDER_PLANS.BILLING_PERIOD,
        PROVIDER_PLANS.PRICE,
        PROVIDERS.ID,
        PROVIDERS.NAME,
        PROVIDERS.SLUG,
        PROVIDERS.LOGO_URL,
    )
        .from(USER_SUBSCRIPTIONS)
        .join(PROVIDER_PLANS).on(PROVIDER_PLANS.ID.eq(USER_SUBSCRIPTIONS.PROVIDER_PLAN_ID))
        .join(PROVIDERS).on(PROVIDERS.ID.eq(PROVIDER_PLANS.PROVIDER_ID))
        .where(USER_SUBSCRIPTIONS.USER_ID.eq(userId))
        .orderBy(PROVIDERS.NAME.asc())
        .fetch()
        .map(::toSubscription)

    fun findOne(userId: UUID, subscriptionId: UUID): Subscription? = dsl.select(
        USER_SUBSCRIPTIONS.ID,
        USER_SUBSCRIPTIONS.STATUS,
        USER_SUBSCRIPTIONS.STARTED_ON,
        USER_SUBSCRIPTIONS.RENEWS_ON,
        USER_SUBSCRIPTIONS.COMMITMENT_ENDS_ON,
        USER_SUBSCRIPTIONS.ACTUAL_PRICE,
        USER_SUBSCRIPTIONS.CURRENCY,
        USER_SUBSCRIPTIONS.AUTO_RENEWS,
        USER_SUBSCRIPTIONS.CANNOT_CANCEL,
        USER_SUBSCRIPTIONS.NOTES,
        PROVIDER_PLANS.NAME,
        PROVIDER_PLANS.BILLING_PERIOD,
        PROVIDER_PLANS.PRICE,
        PROVIDERS.ID,
        PROVIDERS.NAME,
        PROVIDERS.SLUG,
        PROVIDERS.LOGO_URL,
    )
        .from(USER_SUBSCRIPTIONS)
        .join(PROVIDER_PLANS).on(PROVIDER_PLANS.ID.eq(USER_SUBSCRIPTIONS.PROVIDER_PLAN_ID))
        .join(PROVIDERS).on(PROVIDERS.ID.eq(PROVIDER_PLANS.PROVIDER_ID))
        .where(USER_SUBSCRIPTIONS.USER_ID.eq(userId))
        .and(USER_SUBSCRIPTIONS.ID.eq(subscriptionId))
        .fetchOne()
        ?.let(::toSubscription)

    /**
     * Whether this provider exists and is active.
     *
     * Checked here in SQL rather than by calling the availability module's
     * `ProviderRepository`, which would be a class dependency across a feature
     * boundary and the one thing ArchUnit forbids. The provider list the client
     * picked from is served by that module's own endpoint; this is only the
     * server-side check that the id came from there.
     */
    fun providerExists(providerId: UUID): Boolean = dsl.fetchExists(
        DSL.selectFrom(PROVIDERS).where(PROVIDERS.ID.eq(providerId)).and(PROVIDERS.ACTIVE.isTrue),
    )

    /**
     * Finds the current plan row for a provider and plan name, or creates it.
     *
     * `user_subscriptions.provider_plan_id` is `NOT NULL`, so a subscription
     * cannot exist without a plan -- and plans ship unseeded on purpose. The
     * price written here is the one the user supplied about their own bill,
     * which is the only pricing Plotted is entitled to record.
     *
     * `validity` opens at today with no end, matching the convention in
     * `docs/seed/provider-plans.md`. The GiST exclusion constraint refuses a
     * second overlapping period for the same provider, region and plan name,
     * which is why this looks the current row up first rather than inserting
     * optimistically.
     */
    fun findOrCreatePlan(
        providerId: UUID,
        regionCode: String,
        planName: String,
        billingPeriod: BillingPeriod,
        price: BigDecimal,
        currency: String,
    ): UUID {
        val existing = dsl.select(PROVIDER_PLANS.ID)
            .from(PROVIDER_PLANS)
            .where(PROVIDER_PLANS.PROVIDER_ID.eq(providerId))
            .and(PROVIDER_PLANS.REGION_CODE.eq(regionCode))
            .and(PROVIDER_PLANS.NAME.eq(planName))
            .and(DSL.condition("upper_inf({0})", DSL.field(DSL.name("validity"))))
            .fetchOne()
            ?.value1()
        if (existing != null) return existing

        val id = UUID.randomUUID()
        dsl.insertInto(PROVIDER_PLANS)
            .set(PROVIDER_PLANS.ID, id)
            .set(PROVIDER_PLANS.PROVIDER_ID, providerId)
            .set(PROVIDER_PLANS.REGION_CODE, regionCode)
            .set(PROVIDER_PLANS.NAME, planName)
            .set(PROVIDER_PLANS.BILLING_PERIOD, billingPeriod.dbValue)
            .set(PROVIDER_PLANS.PRICE, price)
            .set(PROVIDER_PLANS.CURRENCY, currency)
            .set(PROVIDER_PLANS.AD_SUPPORTED, false)
            .set(
                DSL.field(DSL.name("validity"), String::class.java),
                DSL.field("daterange({0}, NULL)", String::class.java, DSL.`val`(LocalDate.now(clock))),
            )
            .execute()
        return id
    }

    fun insert(
        userId: UUID,
        providerPlanId: UUID,
        price: BigDecimal,
        currency: String,
        status: SubscriptionStatus,
        startedOn: LocalDate,
        renewsOn: LocalDate?,
        commitmentEndsOn: LocalDate?,
        autoRenews: Boolean,
        cannotCancel: Boolean,
        notes: String?,
    ): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        dsl.insertInto(USER_SUBSCRIPTIONS)
            .set(USER_SUBSCRIPTIONS.ID, id)
            .set(USER_SUBSCRIPTIONS.USER_ID, userId)
            .set(USER_SUBSCRIPTIONS.PROVIDER_PLAN_ID, providerPlanId)
            .set(USER_SUBSCRIPTIONS.STATUS, status.dbValue)
            .set(USER_SUBSCRIPTIONS.STARTED_ON, startedOn)
            .set(USER_SUBSCRIPTIONS.RENEWS_ON, renewsOn)
            .set(USER_SUBSCRIPTIONS.COMMITMENT_ENDS_ON, commitmentEndsOn)
            .set(USER_SUBSCRIPTIONS.ACTUAL_PRICE, price)
            .set(USER_SUBSCRIPTIONS.CURRENCY, currency)
            .set(USER_SUBSCRIPTIONS.AUTO_RENEWS, autoRenews)
            .set(USER_SUBSCRIPTIONS.CANNOT_CANCEL, cannotCancel)
            .set(USER_SUBSCRIPTIONS.NOTES, notes)
            .set(USER_SUBSCRIPTIONS.CREATED_AT, now)
            .set(USER_SUBSCRIPTIONS.UPDATED_AT, now)
            .execute()
        return id
    }

    /** Partial update, built as a map so an empty patch is answerable rather than invalid SQL. */
    fun update(
        userId: UUID,
        subscriptionId: UUID,
        status: SubscriptionStatus?,
        renewsOn: LocalDate?,
        clearRenewsOn: Boolean,
        autoRenews: Boolean?,
        cannotCancel: Boolean?,
        notes: String?,
        clearNotes: Boolean,
    ): Boolean {
        val changes = mutableMapOf<org.jooq.Field<*>, Any?>()
        status?.let { changes[USER_SUBSCRIPTIONS.STATUS] = it.dbValue }
        if (clearRenewsOn) changes[USER_SUBSCRIPTIONS.RENEWS_ON] = null else renewsOn?.let { changes[USER_SUBSCRIPTIONS.RENEWS_ON] = it }
        autoRenews?.let { changes[USER_SUBSCRIPTIONS.AUTO_RENEWS] = it }
        cannotCancel?.let { changes[USER_SUBSCRIPTIONS.CANNOT_CANCEL] = it }
        if (clearNotes) changes[USER_SUBSCRIPTIONS.NOTES] = null else notes?.let { changes[USER_SUBSCRIPTIONS.NOTES] = it }

        if (changes.isEmpty()) return findOne(userId, subscriptionId) != null
        changes[USER_SUBSCRIPTIONS.UPDATED_AT] = OffsetDateTime.now(clock)

        return dsl.update(USER_SUBSCRIPTIONS)
            .set(changes)
            .where(USER_SUBSCRIPTIONS.USER_ID.eq(userId))
            .and(USER_SUBSCRIPTIONS.ID.eq(subscriptionId))
            .execute() > 0
    }

    fun delete(userId: UUID, subscriptionId: UUID): Boolean = dsl.deleteFrom(USER_SUBSCRIPTIONS)
        .where(USER_SUBSCRIPTIONS.USER_ID.eq(userId))
        .and(USER_SUBSCRIPTIONS.ID.eq(subscriptionId))
        .execute() > 0

    private fun toSubscription(record: org.jooq.Record): Subscription = Subscription(
        id = record[USER_SUBSCRIPTIONS.ID]!!,
        providerId = record[PROVIDERS.ID]!!,
        providerName = record[PROVIDERS.NAME]!!,
        providerSlug = record[PROVIDERS.SLUG]!!,
        providerLogoUrl = record[PROVIDERS.LOGO_URL],
        planName = record[PROVIDER_PLANS.NAME]!!,
        billingPeriod = BillingPeriod.fromDb(record[PROVIDER_PLANS.BILLING_PERIOD]!!),
        // The user's own figure wins over the plan's list price: a grandfathered
        // rate or a bundle discount is what they actually pay, and that is the
        // number the optimiser has to minimise.
        price = record[USER_SUBSCRIPTIONS.ACTUAL_PRICE] ?: record[PROVIDER_PLANS.PRICE]!!,
        currency = record[USER_SUBSCRIPTIONS.CURRENCY]!!.trim(),
        status = SubscriptionStatus.fromDb(record[USER_SUBSCRIPTIONS.STATUS]!!),
        startedOn = record[USER_SUBSCRIPTIONS.STARTED_ON]!!,
        renewsOn = record[USER_SUBSCRIPTIONS.RENEWS_ON],
        autoRenews = record[USER_SUBSCRIPTIONS.AUTO_RENEWS]!!,
        cannotCancel = record[USER_SUBSCRIPTIONS.CANNOT_CANCEL]!!,
        commitmentEndsOn = record[USER_SUBSCRIPTIONS.COMMITMENT_ENDS_ON],
        notes = record[USER_SUBSCRIPTIONS.NOTES],
    )
}
