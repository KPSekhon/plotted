package app.plotted.identity.api

import app.plotted.identity.domain.AccessPolicy
import app.plotted.identity.domain.CommitmentPreference
import app.plotted.identity.domain.UserAccount
import app.plotted.identity.domain.UserSettings
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Schema(description = "Create an account. The response also establishes a session.")
data class RegisterRequest(
    @field:Email(message = "must be a valid email address")
    @field:NotBlank
    @field:Size(max = 320)
    val email: String,
    // 12 characters rather than 8: this is the only secret protecting a detailed
    // record of what someone watches. No composition rules, which push people
    // towards predictable substitutions rather than length.
    @field:Size(min = 12, max = 200, message = "must be between 12 and 200 characters")
    val password: String,
    @field:NotBlank
    @field:Size(max = 120)
    val displayName: String,
    @field:Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter region code")
    val regionCode: String? = null,
    @field:Size(max = 64)
    val timezone: String? = null,
)

data class LoginRequest(
    @field:NotBlank
    val email: String,
    @field:NotBlank
    val password: String,
)

@Schema(
    description =
    "The access token is short-lived and belongs in memory. The refresh token is " +
        "returned as an HttpOnly cookie and never appears in this body.",
)
data class SessionResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val expiresAt: Instant,
    val user: UserResponse,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
    val regionCode: String,
    val timezone: String,
    val preferredCurrency: String,
    val onboardingStatus: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(account: UserAccount): UserResponse = UserResponse(
            id = account.id,
            email = account.email,
            displayName = account.displayName,
            regionCode = account.regionCode,
            timezone = account.timezone,
            preferredCurrency = account.preferredCurrency,
            onboardingStatus = account.onboardingStatus.dbValue,
            createdAt = account.createdAt,
        )
    }
}

data class UserSettingsResponse(
    val maximumMonthlyBudget: BigDecimal?,
    val maximumActiveServices: Int?,
    val maximumMonthlySwitches: Int?,
    val defaultAvailableMinutes: Int?,
    val defaultAccessPolicy: String,
    val defaultNoveltyPreference: BigDecimal,
    val defaultCommitmentPreference: String,
    val allowPaidRentals: Boolean,
    val maximumRentalPrice: BigDecimal?,
    val allowPhysicalMedia: Boolean,
    val weeklyViewingMinutesOverride: Int?,
    val updatedAt: Instant,
) {
    companion object {
        fun from(settings: UserSettings): UserSettingsResponse = UserSettingsResponse(
            maximumMonthlyBudget = settings.maximumMonthlyBudget,
            maximumActiveServices = settings.maximumActiveServices,
            maximumMonthlySwitches = settings.maximumMonthlySwitches,
            defaultAvailableMinutes = settings.defaultAvailableMinutes,
            defaultAccessPolicy = settings.defaultAccessPolicy.dbValue,
            defaultNoveltyPreference = settings.defaultNoveltyPreference,
            defaultCommitmentPreference = settings.defaultCommitmentPreference.dbValue,
            allowPaidRentals = settings.allowPaidRentals,
            maximumRentalPrice = settings.maximumRentalPrice,
            allowPhysicalMedia = settings.allowPhysicalMedia,
            weeklyViewingMinutesOverride = settings.weeklyViewingMinutesOverride,
            updatedAt = settings.updatedAt,
        )
    }
}

@Schema(description = "Partial update. Omitted fields are left unchanged.")
data class UpdateUserSettingsRequest(
    @field:DecimalMin("0.00")
    @field:DecimalMax("10000.00")
    val maximumMonthlyBudget: BigDecimal? = null,
    @field:Min(0)
    @field:Max(20)
    val maximumActiveServices: Int? = null,
    @field:Min(0)
    @field:Max(20)
    val maximumMonthlySwitches: Int? = null,
    @field:Min(5)
    @field:Max(1440)
    val defaultAvailableMinutes: Int? = null,
    val defaultAccessPolicy: AccessPolicy? = null,
    @field:DecimalMin("0.000")
    @field:DecimalMax("1.000")
    val defaultNoveltyPreference: BigDecimal? = null,
    val defaultCommitmentPreference: CommitmentPreference? = null,
    val allowPaidRentals: Boolean? = null,
    @field:DecimalMin("0.00")
    @field:DecimalMax("1000.00")
    val maximumRentalPrice: BigDecimal? = null,
    val allowPhysicalMedia: Boolean? = null,
    @field:Min(0)
    @field:Max(10080)
    val weeklyViewingMinutesOverride: Int? = null,
)
