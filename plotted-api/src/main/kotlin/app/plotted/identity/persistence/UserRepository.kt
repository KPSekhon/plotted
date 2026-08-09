package app.plotted.identity.persistence

import app.plotted.generated.jooq.tables.references.USERS
import app.plotted.generated.jooq.tables.references.USER_SETTINGS
import app.plotted.identity.domain.AccessPolicy
import app.plotted.identity.domain.CommitmentPreference
import app.plotted.identity.domain.OnboardingStatus
import app.plotted.identity.domain.UserAccount
import app.plotted.identity.domain.UserSettings
import app.plotted.identity.domain.UserSettingsPatch
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class UserRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    /**
     * Case-insensitive by virtue of the CITEXT column, not by lowering in Kotlin.
     * Putting the rule in the schema means every future query gets it for free.
     */
    fun findByEmail(email: String): StoredUser? =
        dsl.select(USERS.ID, USERS.EMAIL, USERS.PASSWORD_HASH, USERS.DISPLAY_NAME, USERS.REGION_CODE, USERS.TIMEZONE)
            .select(USERS.PREFERRED_CURRENCY, USERS.ONBOARDING_STATUS, USERS.CREATED_AT)
            .from(USERS)
            .where(USERS.EMAIL.eq(email))
            .and(USERS.DELETED_AT.isNull)
            .fetchOne()
            ?.let { StoredUser(toAccount(it), it[USERS.PASSWORD_HASH]) }

    fun findById(userId: UUID): UserAccount? = dsl.select(USERS.ID, USERS.EMAIL, USERS.DISPLAY_NAME, USERS.REGION_CODE, USERS.TIMEZONE)
        .select(USERS.PREFERRED_CURRENCY, USERS.ONBOARDING_STATUS, USERS.CREATED_AT)
        .from(USERS)
        .where(USERS.ID.eq(userId))
        .and(USERS.DELETED_AT.isNull)
        .fetchOne()
        ?.let(::toAccount)

    fun emailExists(email: String): Boolean = dsl.fetchExists(dsl.selectFrom(USERS).where(USERS.EMAIL.eq(email)))

    /**
     * Whether an account is still live. Runs on every authenticated request, so
     * it selects nothing at all — the primary key index answers it.
     *
     * The `deleted_at` predicate matches [findById] deliberately. A soft-deleted
     * account is one identity already declines to resolve, and letting it keep
     * authenticating would make "deleted" mean two different things depending on
     * which door you came through.
     */
    fun exists(userId: UUID): Boolean = dsl.fetchExists(
        dsl.selectFrom(USERS).where(USERS.ID.eq(userId)).and(USERS.DELETED_AT.isNull),
    )

    fun insert(
        id: UUID,
        email: String,
        passwordHash: String,
        displayName: String,
        regionCode: String,
        timezone: String,
        currency: String,
    ): UserAccount {
        val now = OffsetDateTime.now(clock)
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, email)
            .set(USERS.PASSWORD_HASH, passwordHash)
            .set(USERS.DISPLAY_NAME, displayName)
            .set(USERS.REGION_CODE, regionCode)
            .set(USERS.TIMEZONE, timezone)
            .set(USERS.PREFERRED_CURRENCY, currency)
            .set(USERS.ONBOARDING_STATUS, OnboardingStatus.REGISTERED.dbValue)
            .set(USERS.CREATED_AT, now)
            .set(USERS.UPDATED_AT, now)
            .execute()
        return UserAccount(
            id = id,
            email = email,
            displayName = displayName,
            regionCode = regionCode,
            timezone = timezone,
            preferredCurrency = currency,
            onboardingStatus = OnboardingStatus.REGISTERED,
            createdAt = now.toInstant(),
        )
    }

    fun insertDefaultSettings(userId: UUID) {
        dsl.insertInto(USER_SETTINGS)
            .set(USER_SETTINGS.USER_ID, userId)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now(clock))
            .onConflictDoNothing()
            .execute()
    }

    fun findSettings(userId: UUID): UserSettings? = dsl.selectFrom(USER_SETTINGS)
        .where(USER_SETTINGS.USER_ID.eq(userId))
        .fetchOne()
        ?.let { record ->
            // jOOQ generates every record attribute as nullable regardless of
            // the column's nullability, so NOT NULL columns are asserted here.
            UserSettings(
                userId = userId,
                maximumMonthlyBudget = record.maximumMonthlyBudget,
                maximumActiveServices = record.maximumActiveServices,
                maximumMonthlySwitches = record.maximumMonthlySwitches,
                defaultAvailableMinutes = record.defaultAvailableMinutes,
                defaultAccessPolicy = AccessPolicy.fromDb(record.defaultAccessPolicy!!),
                defaultNoveltyPreference = record.defaultNoveltyPreference!!,
                defaultCommitmentPreference = CommitmentPreference.fromDb(record.defaultCommitmentPreference!!),
                allowPaidRentals = record.allowPaidRentals!!,
                maximumRentalPrice = record.maximumRentalPrice,
                allowPhysicalMedia = record.allowPhysicalMedia!!,
                weeklyViewingMinutesOverride = record.weeklyViewingMinutesOverride,
                updatedAt = record.updatedAt!!.toInstant(),
            )
        }

    /** Applies only the fields the caller actually set. */
    fun updateSettings(userId: UUID, patch: UserSettingsPatch): Boolean {
        val update = dsl.update(USER_SETTINGS).set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now(clock))
        patch.maximumMonthlyBudget?.let { update.set(USER_SETTINGS.MAXIMUM_MONTHLY_BUDGET, it) }
        patch.maximumActiveServices?.let { update.set(USER_SETTINGS.MAXIMUM_ACTIVE_SERVICES, it) }
        patch.maximumMonthlySwitches?.let { update.set(USER_SETTINGS.MAXIMUM_MONTHLY_SWITCHES, it) }
        patch.defaultAvailableMinutes?.let { update.set(USER_SETTINGS.DEFAULT_AVAILABLE_MINUTES, it) }
        patch.defaultAccessPolicy?.let { update.set(USER_SETTINGS.DEFAULT_ACCESS_POLICY, it.dbValue) }
        patch.defaultNoveltyPreference?.let { update.set(USER_SETTINGS.DEFAULT_NOVELTY_PREFERENCE, it) }
        patch.defaultCommitmentPreference?.let { update.set(USER_SETTINGS.DEFAULT_COMMITMENT_PREFERENCE, it.dbValue) }
        patch.allowPaidRentals?.let { update.set(USER_SETTINGS.ALLOW_PAID_RENTALS, it) }
        patch.maximumRentalPrice?.let { update.set(USER_SETTINGS.MAXIMUM_RENTAL_PRICE, it) }
        patch.allowPhysicalMedia?.let { update.set(USER_SETTINGS.ALLOW_PHYSICAL_MEDIA, it) }
        patch.weeklyViewingMinutesOverride?.let { update.set(USER_SETTINGS.WEEKLY_VIEWING_MINUTES_OVERRIDE, it) }
        return update.where(USER_SETTINGS.USER_ID.eq(userId)).execute() > 0
    }

    private fun toAccount(record: Record): UserAccount = UserAccount(
        id = record[USERS.ID]!!,
        email = record[USERS.EMAIL]!!,
        displayName = record[USERS.DISPLAY_NAME]!!,
        regionCode = record[USERS.REGION_CODE]!!.trim(),
        timezone = record[USERS.TIMEZONE]!!,
        preferredCurrency = record[USERS.PREFERRED_CURRENCY]!!.trim(),
        onboardingStatus = OnboardingStatus.fromDb(record[USERS.ONBOARDING_STATUS]!!),
        createdAt = record[USERS.CREATED_AT]!!.toInstant(),
    )

    /** Carries the password hash, which never leaves this module. */
    data class StoredUser(
        val account: UserAccount,
        val passwordHash: String?,
    )
}
