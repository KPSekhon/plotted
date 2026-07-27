package app.plotted.identity.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Onboarding is abandonable at every step (spec section 8.1): skipping Pilot
 * Season costs accuracy, not access. This status records how far someone got, and
 * is never used to gate a feature.
 */
enum class OnboardingStatus(
    val dbValue: String,
) {
    REGISTERED("registered"),
    REGION_SELECTED("region_selected"),
    SERVICES_SELECTED("services_selected"),
    PILOT_STARTED("pilot_started"),
    PILOT_COMPLETE("pilot_complete"),
    ACTIVE("active"),
    ;

    companion object {
        fun fromDb(value: String): OnboardingStatus = entries.firstOrNull { it.dbValue == value }
            ?: error("Unknown onboarding_status '$value'")
    }
}

/**
 * The wire form of these enums is the stored value, in both directions. Sending
 * `ACTIVE_SUBSCRIPTIONS_ONLY` and receiving `active_subscriptions_only` for the
 * same field is the sort of asymmetry every client ends up writing a translation
 * layer for.
 */
enum class AccessPolicy(
    @get:JsonValue val dbValue: String,
) {
    ACTIVE_SUBSCRIPTIONS_ONLY("active_subscriptions_only"),
    INCLUDE_FREE("include_free"),
    ALL_ACCESS("all_access"),
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromDb(value: String): AccessPolicy = entries.firstOrNull { it.dbValue == value } ?: error("Unknown access policy '$value'")
    }
}

enum class CommitmentPreference(
    @get:JsonValue val dbValue: String,
) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromDb(value: String): CommitmentPreference =
            entries.firstOrNull { it.dbValue == value } ?: error("Unknown commitment preference '$value'")
    }
}

data class UserAccount(
    val id: UUID,
    val email: String,
    val displayName: String,
    val regionCode: String,
    val timezone: String,
    val preferredCurrency: String,
    val onboardingStatus: OnboardingStatus,
    val createdAt: Instant,
)

/**
 * Defaults that make Tonight Mode completable in under four taps (spec section
 * 6.1). Every one of these exists so the fast path does not have to ask.
 */
data class UserSettings(
    val userId: UUID,
    val maximumMonthlyBudget: BigDecimal?,
    val maximumActiveServices: Int?,
    val maximumMonthlySwitches: Int?,
    val defaultAvailableMinutes: Int?,
    val defaultAccessPolicy: AccessPolicy,
    val defaultNoveltyPreference: BigDecimal,
    val defaultCommitmentPreference: CommitmentPreference,
    val allowPaidRentals: Boolean,
    val maximumRentalPrice: BigDecimal?,
    val allowPhysicalMedia: Boolean,
    /**
     * Overrides the trailing eight-week viewing estimate. The estimate drives
     * cancellation advice, so it has to be visible and correctable rather than
     * silently derived (spec section 6.3).
     */
    val weeklyViewingMinutesOverride: Int?,
    val updatedAt: Instant,
)

/** A partial update. `null` means "leave alone"; clearing a value is a separate concern. */
data class UserSettingsPatch(
    val maximumMonthlyBudget: BigDecimal? = null,
    val maximumActiveServices: Int? = null,
    val maximumMonthlySwitches: Int? = null,
    val defaultAvailableMinutes: Int? = null,
    val defaultAccessPolicy: AccessPolicy? = null,
    val defaultNoveltyPreference: BigDecimal? = null,
    val defaultCommitmentPreference: CommitmentPreference? = null,
    val allowPaidRentals: Boolean? = null,
    val maximumRentalPrice: BigDecimal? = null,
    val allowPhysicalMedia: Boolean? = null,
    val weeklyViewingMinutesOverride: Int? = null,
)
