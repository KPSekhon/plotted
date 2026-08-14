package app.plotted.demo.persistence

import app.plotted.generated.jooq.tables.references.PROVIDERS
import app.plotted.generated.jooq.tables.references.PROVIDER_PLANS
import app.plotted.generated.jooq.tables.references.TITLES
import app.plotted.generated.jooq.tables.references.TITLE_AVAILABILITY
import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.generated.jooq.tables.references.USER_SETTINGS
import app.plotted.generated.jooq.tables.references.USER_SUBSCRIPTIONS
import app.plotted.generated.jooq.tables.references.WATCHLISTS
import app.plotted.generated.jooq.tables.references.WATCHLIST_ITEMS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Builds and sweeps demo accounts.
 *
 * ### Why this writes to other modules' tables
 *
 * A demo account needs a user, a watchlist, watchlist items and subscriptions —
 * rows owned by four different feature modules. Going through their services
 * would mean `demo` importing from `identity`, `watchlist` and `subscriptions`,
 * which is the feature-to-feature coupling `ModuleBoundaryTest` exists to
 * prevent. ADR 0008 draws the line at *classes* crossing a module boundary and
 * explicitly permits cross-module SQL, so the fixture is assembled here with the
 * typed jOOQ API — which at least means the compiler checks every column.
 *
 * The cost, stated plainly: this bypasses the domain services and so does not
 * get their invariants for free. The one that matters is `cannot_cancel`, which
 * `SubscriptionService` derives from the commitment date rather than trusting.
 * [insertSubscription] derives it the same way for the same reason — a demo
 * showing a flag that disagrees with the date beside it would be demonstrating
 * a bug.
 *
 * ### What this deliberately does not do
 *
 * It does not resolve runtimes or coverage. Both have rules that live in the
 * catalogue and availability modules and are easy to get subtly wrong — a
 * series' runtime is summed from real episodes rather than estimated, and only
 * subscription-included offers count as coverage. [DemoService] reads those
 * through the shared-kernel directories instead, so there is exactly one
 * definition of each. What is left here is the part that genuinely has no home
 * anywhere else: writing fixture rows.
 */
@Repository
class DemoRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    fun createUser(displayName: String, regionCode: String, lifetime: Duration): UUID {
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        dsl.insertInto(USERS)
            .set(USERS.ID, userId)
            // `.invalid` is reserved by RFC 2606 precisely so it can never be
            // delivered to. A demo account must not be able to receive mail, and
            // must not collide with a real address someone later registers.
            .set(USERS.EMAIL, "demo-${userId.toString().take(EMAIL_SUFFIX_LENGTH)}@demo.plotted.invalid")
            // Null, not a hash of something guessable. There is no password that
            // works, so the login path cannot reach this account at all.
            .set(USERS.PASSWORD_HASH, null as String?)
            .set(USERS.DISPLAY_NAME, displayName)
            .set(USERS.REGION_CODE, regionCode)
            .set(USERS.ONBOARDING_STATUS, "active")
            .set(USERS.IS_DEMO, true)
            .set(USERS.EXPIRES_AT, now.plus(lifetime))
            .set(USERS.CREATED_AT, now)
            .set(USERS.UPDATED_AT, now)
            .execute()

        dsl.insertInto(USER_SETTINGS)
            .set(USER_SETTINGS.USER_ID, userId)
            .set(USER_SETTINGS.UPDATED_AT, now)
            .execute()

        return userId
    }

    fun createWatchlist(userId: UUID, name: String): UUID {
        val watchlistId = UUID.randomUUID()
        dsl.insertInto(WATCHLISTS)
            .set(WATCHLISTS.ID, watchlistId)
            .set(WATCHLISTS.USER_ID, userId)
            .set(WATCHLISTS.NAME, name)
            .set(WATCHLISTS.IS_DEFAULT, true)
            .set(WATCHLISTS.CREATED_AT, OffsetDateTime.now(clock))
            .execute()
        return watchlistId
    }

    /**
     * Candidate titles for the demo list: those a subscription service actually
     * carries here, curated picks first, then most-carried, then by name so the
     * demo is the same every time.
     *
     * ### Why there is a curated tier at all
     *
     * Ranking by carrier count alone is a fine tie-break and a poor persona. It
     * returns whatever happens to be on the most platforms, which reads as a
     * list nobody chose — and the demo's job is to look like a real person's
     * watchlist, which means having opinions in it. `demo/preferred-titles.txt`
     * holds those opinions, versioned, for the same reason the curated half of
     * the seed is.
     *
     * It only *reorders*. Every existing rule still applies: a title nothing
     * carries on a subscription never appears however high it is listed, and
     * the caller still discards anything whose runtime is unknown. A preference
     * cannot promote a title past a filter, which is the point — otherwise the
     * demo would be a place where the product's own rules quietly do not hold.
     *
     * Ids only. Whether a title is *usable* is decided by the caller through
     * `TitleDirectory`, because the answer differs between films and series and
     * the catalogue owns that rule. Over-fetches so the caller has spares.
     */
    fun findCandidateTitleIds(regionCode: String, limit: Int, preferredExternalIds: List<String> = emptyList()): List<UUID> {
        val carriers = DSL.countDistinct(TITLE_AVAILABILITY.PROVIDER_ID)
        // 0 sorts before 1, so the curated tier leads. Built from a bound list
        // rather than interpolated, so an id from the file cannot reach the
        // statement as SQL.
        val curatedFirst = DSL.`when`(TITLES.EXTERNAL_ID.`in`(preferredExternalIds), 0).otherwise(1)

        return dsl.select(TITLES.ID, TITLES.NAME, carriers, curatedFirst)
            .from(TITLES)
            .join(TITLE_AVAILABILITY).on(TITLE_AVAILABILITY.TITLE_ID.eq(TITLES.ID))
            .where(TITLE_AVAILABILITY.REGION_CODE.eq(regionCode))
            .and(TITLE_AVAILABILITY.ACCESS_TYPE.eq("subscription"))
            .and(TITLE_AVAILABILITY.ACTIVE.isTrue)
            .groupBy(TITLES.ID, TITLES.NAME, TITLES.EXTERNAL_ID)
            .orderBy(curatedFirst.asc(), carriers.desc(), TITLES.NAME.asc())
            .limit(limit)
            .fetch(TITLES.ID)
            .filterNotNull()
    }

    /**
     * One watchlist row.
     *
     * [completedAt] exists so the persona can have finished a few things. That
     * is not decoration: End Credits' completion rate joins on
     * `completed_at >= accepted_at`, so without it the demo's rate has an empty
     * numerator, and the watchlist's own "finished and set aside" group never
     * appears on screen at all.
     */
    fun insertWatchlistItem(watchlistId: UUID, titleId: UUID, priority: Int, desiredBy: LocalDate?, completedAt: OffsetDateTime? = null) {
        dsl.insertInto(WATCHLIST_ITEMS)
            .set(WATCHLIST_ITEMS.ID, UUID.randomUUID())
            .set(WATCHLIST_ITEMS.WATCHLIST_ID, watchlistId)
            .set(WATCHLIST_ITEMS.TITLE_ID, titleId)
            .set(WATCHLIST_ITEMS.PRIORITY, priority.toShort())
            // The status and the timestamp are set together. A row saying
            // "completed" with no completed_at, or the reverse, is a state the
            // rest of the product has to defend against for no reason.
            .set(WATCHLIST_ITEMS.STATUS, if (completedAt == null) "pending" else "completed")
            .set(WATCHLIST_ITEMS.ADDED_AT, OffsetDateTime.now(clock))
            .set(WATCHLIST_ITEMS.DESIRED_BY_DATE, desiredBy)
            .set(WATCHLIST_ITEMS.COMPLETED_AT, completedAt)
            .set(WATCHLIST_ITEMS.SOURCE, "demo")
            .onConflictDoNothing()
            .execute()
    }

    /**
     * The cheapest currently-priced plan per provider, as `providerId -> planId`.
     *
     * A subscription row points at a *plan*, not a provider, so the persona
     * cannot be given one without this. Only open price periods count: a closed
     * row is a historical price, and a demo quoting one would be showing a
     * number that is wrong on purpose. Cheapest tier per provider for the same
     * reason `SubscriptionDirectoryAdapter` picks it — which tier to buy is not
     * the question this product answers.
     */
    fun findCurrentPlanIdsByProvider(regionCode: String): Map<UUID, UUID> = dsl
        .select(PROVIDER_PLANS.ID, PROVIDER_PLANS.PROVIDER_ID, PROVIDER_PLANS.PRICE)
        .from(PROVIDER_PLANS)
        .join(PROVIDERS).on(PROVIDERS.ID.eq(PROVIDER_PLANS.PROVIDER_ID))
        .where(PROVIDER_PLANS.REGION_CODE.eq(regionCode))
        .and(PROVIDERS.ACTIVE.isTrue)
        .and(DSL.condition("upper_inf({0})", DSL.field(DSL.name("validity"))))
        .fetch()
        .groupBy { it[PROVIDER_PLANS.PROVIDER_ID]!! }
        .mapValues { (_, rows) ->
            rows.minBy { it[PROVIDER_PLANS.PRICE] ?: BigDecimal.ZERO }[PROVIDER_PLANS.ID]!!
        }

    fun insertSubscription(userId: UUID, planId: UUID, startedOn: LocalDate, commitmentEndsOn: LocalDate?) {
        val now = OffsetDateTime.now(clock)
        dsl.insertInto(USER_SUBSCRIPTIONS)
            .set(USER_SUBSCRIPTIONS.ID, UUID.randomUUID())
            .set(USER_SUBSCRIPTIONS.USER_ID, userId)
            .set(USER_SUBSCRIPTIONS.PROVIDER_PLAN_ID, planId)
            .set(USER_SUBSCRIPTIONS.STATUS, "active")
            .set(USER_SUBSCRIPTIONS.STARTED_ON, startedOn)
            .set(USER_SUBSCRIPTIONS.RENEWS_ON, nextRenewal(startedOn))
            .set(USER_SUBSCRIPTIONS.COMMITMENT_ENDS_ON, commitmentEndsOn)
            // Derived, never passed in — the same rule SubscriptionService
            // follows, so the flag cannot disagree with the date beside it.
            .set(
                USER_SUBSCRIPTIONS.CANNOT_CANCEL,
                commitmentEndsOn != null && commitmentEndsOn.isAfter(LocalDate.now(clock)),
            )
            .set(USER_SUBSCRIPTIONS.AUTO_RENEWS, true)
            // Set to the plan's own researched price rather than left null.
            //
            // This used to be null so the persona paid the cited figure rather
            // than a made-up personal rate, which was right while every price
            // was equally trusted. It is not right now: since V18 the optimiser
            // only spends prices somebody confirmed, and a null here leaves the
            // demo's services priced REFERENCE, excluded from the model, and
            // Cancel Culture with nothing to say on the account that exists to
            // demonstrate it.
            //
            // The number is not invented -- it is the same researched figure,
            // copied. What changes is the claim: on a synthetic account the
            // persona confirming a fixture price is exactly as legitimate as
            // the rest of the fixture, and the subscriptions screen says in so
            // many words that this data was generated.
            .set(
                USER_SUBSCRIPTIONS.ACTUAL_PRICE,
                DSL.field(
                    dsl.select(PROVIDER_PLANS.PRICE).from(PROVIDER_PLANS).where(PROVIDER_PLANS.ID.eq(planId)),
                ),
            )
            .set(USER_SUBSCRIPTIONS.CREATED_AT, now)
            .set(USER_SUBSCRIPTIONS.UPDATED_AT, now)
            .execute()
    }

    /** The next monthly renewal after today, from a start date in the past. */
    private fun nextRenewal(startedOn: LocalDate): LocalDate {
        val today = LocalDate.now(clock)
        var renewal = startedOn
        while (!renewal.isAfter(today)) renewal = renewal.plusMonths(1)
        return renewal
    }

    /**
     * Deletes demo accounts past their expiry.
     *
     * Everything else goes with them: `users` is the root of an `ON DELETE
     * CASCADE` chain covering watchlists, subscriptions, sessions and the
     * decision log, so this one statement is the whole sweep. The `is_demo`
     * predicate is the only thing between this and real accounts, which is why
     * the schema also refuses to let a real account carry an expiry at all.
     */
    fun deleteExpired(): Int = dsl.deleteFrom(USERS)
        .where(USERS.IS_DEMO.isTrue)
        .and(USERS.EXPIRES_AT.lessThan(OffsetDateTime.now(clock)))
        .execute()

    fun countLiveDemoAccounts(): Int = dsl.fetchCount(
        USERS,
        USERS.IS_DEMO.isTrue.and(USERS.EXPIRES_AT.greaterOrEqual(OffsetDateTime.now(clock))),
    )

    private companion object {
        const val EMAIL_SUFFIX_LENGTH = 8
    }
}
