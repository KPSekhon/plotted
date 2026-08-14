package app.plotted.subscriptions.domain

import app.plotted.platform.spi.SubscriptionDirectory
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A service the user pays for.
 *
 * The price is the user's own: `provider_plans` ships deliberately unseeded
 * because Plotted has no way to verify what a service costs today, and invented
 * pricing would put fabricated money into the phase 5 optimiser's objective
 * function (see `docs/seed/provider-plans.md`). What a person tells us they pay
 * is not invented — it is the most reliable figure available, because they are
 * the one being billed. `actualPrice` carries it, and the plan row records what
 * they said the plan is called.
 */
data class Subscription(
    val id: UUID,
    val providerId: UUID,
    val providerName: String,
    val providerSlug: String,
    val providerLogoUrl: String?,
    val planName: String,
    val billingPeriod: BillingPeriod,
    val price: BigDecimal,
    val currency: String,
    val status: SubscriptionStatus,
    val startedOn: LocalDate,
    val renewsOn: LocalDate?,
    val autoRenews: Boolean,
    /**
     * Inside a commitment that cannot be cancelled yet -- an annual plan paid up
     * front, a contract term. The optimiser must treat this as a hard constraint
     * rather than a cost, because advising someone to cancel something they
     * cannot cancel is advice that destroys trust in everything else it says.
     */
    val cannotCancel: Boolean,
    val commitmentEndsOn: LocalDate?,
    val notes: String?,
    /**
     * Where [price] came from. `USER_ENTERED` when they typed it, otherwise
     * whatever the plan row claims -- which today is always `REFERENCE`.
     *
     * Carried rather than derived at the point of use, because the fallback to
     * the plan's list price happens in the repository and every caller
     * downstream of it saw a bare number with no way to tell the difference.
     */
    val priceProvenance: SubscriptionDirectory.PriceProvenance,
) {
    /**
     * What this costs per month, for comparing plans billed on different cycles.
     *
     * Annual is divided rather than annualised because every other number on the
     * subscriptions screen is monthly, and a screen that mixes the two is how
     * someone concludes their annual plan costs twelve times what it does.
     */
    val monthlyCost: BigDecimal get() = billingPeriod.toMonthly(price)
}

enum class BillingPeriod(val dbValue: String, val months: Int) {
    MONTHLY("monthly", 1),
    QUARTERLY("quarterly", 3),
    ANNUAL("annual", 12),
    ;

    fun toMonthly(price: BigDecimal): BigDecimal =
        if (months == 1) price else price.divide(BigDecimal(months), 2, java.math.RoundingMode.HALF_UP)

    companion object {
        fun fromDb(value: String): BillingPeriod = entries.firstOrNull { it.dbValue == value } ?: error("Unknown billing_period '$value'")

        fun parse(value: String): BillingPeriod? = entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) }
    }
}

enum class SubscriptionStatus(val dbValue: String) {
    ACTIVE("active"),
    PAUSED("paused"),
    CANCELLED("cancelled"),
    TRIAL("trial"),
    LAPSED("lapsed"),
    ;

    /**
     * Whether this is currently costing money and providing access.
     *
     * A trial counts: it provides access now, and the fact that it is about to
     * start costing money is precisely what the renewal warning is for.
     */
    val isCurrent: Boolean get() = this == ACTIVE || this == TRIAL

    companion object {
        fun fromDb(value: String): SubscriptionStatus =
            entries.firstOrNull { it.dbValue == value } ?: error("Unknown subscription status '$value'")

        fun parse(value: String): SubscriptionStatus? = entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) }
    }
}
