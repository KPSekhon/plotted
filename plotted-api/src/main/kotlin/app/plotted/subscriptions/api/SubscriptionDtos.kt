package app.plotted.subscriptions.api

import app.plotted.subscriptions.domain.Subscription
import app.plotted.subscriptions.domain.SubscriptionService
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class SubscriptionResponse(
    val id: UUID,
    val providerId: UUID,
    val providerName: String,
    val providerSlug: String,
    val providerLogoUrl: String?,
    val planName: String,
    val billingPeriod: String,
    @Schema(description = "What the user told us they pay, per billing period.")
    val price: BigDecimal,
    @Schema(description = "The same figure expressed per month, so plans on different cycles compare.")
    val monthlyCost: BigDecimal,
    val currency: String,
    val status: String,
    val startedOn: LocalDate,
    val renewsOn: LocalDate?,
    val autoRenews: Boolean,
    @Schema(description = "Inside a commitment. Phase 5 treats this as a hard constraint, never a cost.")
    val cannotCancel: Boolean,
    val commitmentEndsOn: LocalDate?,
    val notes: String?,
) {
    companion object {
        fun from(subscription: Subscription): SubscriptionResponse = SubscriptionResponse(
            id = subscription.id,
            providerId = subscription.providerId,
            providerName = subscription.providerName,
            providerSlug = subscription.providerSlug,
            providerLogoUrl = subscription.providerLogoUrl,
            planName = subscription.planName,
            billingPeriod = subscription.billingPeriod.dbValue,
            price = subscription.price,
            monthlyCost = subscription.monthlyCost,
            currency = subscription.currency,
            status = subscription.status.dbValue,
            startedOn = subscription.startedOn,
            renewsOn = subscription.renewsOn,
            autoRenews = subscription.autoRenews,
            cannotCancel = subscription.cannotCancel,
            commitmentEndsOn = subscription.commitmentEndsOn,
            notes = subscription.notes,
        )
    }
}

data class SubscriptionListResponse(
    val subscriptions: List<SubscriptionResponse>,
    @Schema(description = "Total monthly cost of active and trial subscriptions only.")
    val monthlyTotal: BigDecimal,
    val currency: String,
    val countedSubscriptions: Int,
) {
    companion object {
        fun from(summary: SubscriptionService.SubscriptionSummary): SubscriptionListResponse = SubscriptionListResponse(
            subscriptions = summary.subscriptions.map(SubscriptionResponse::from),
            monthlyTotal = summary.monthlyTotal,
            currency = summary.currency,
            countedSubscriptions = summary.countedSubscriptions,
        )
    }
}

@Schema(
    description =
    "Records a subscription the user pays for. Price is supplied by the user rather than " +
        "looked up: Plotted deliberately ships no pricing data, because a figure it invented " +
        "would flow straight into the cancellation optimiser's objective.",
)
data class CreateSubscriptionRequest(
    @field:NotNull
    val providerId: UUID?,
    @field:Size(max = 120)
    @Schema(description = "The plan as the user knows it, for example 'Standard with ads'. Defaults to 'Standard'.")
    val planName: String? = null,
    @Schema(
        description = "One of monthly, quarterly, annual.",
        allowableValues = ["monthly", "quarterly", "annual"],
    )
    val billingPeriod: String? = null,
    @field:NotNull
    @field:DecimalMin("0.0")
    val price: BigDecimal?,
    @field:Size(min = 3, max = 3)
    val currency: String? = null,
    @Schema(
        description = "One of active, paused, cancelled, trial, lapsed. Defaults to active.",
        allowableValues = ["active", "paused", "cancelled", "trial", "lapsed"],
    )
    val status: String? = null,
    val startedOn: LocalDate? = null,
    val renewsOn: LocalDate? = null,
    val commitmentEndsOn: LocalDate? = null,
    val autoRenews: Boolean = true,
    val cannotCancel: Boolean = false,
    @field:Size(max = 2_000)
    val notes: String? = null,
)

data class UpdateSubscriptionRequest(
    @Schema(allowableValues = ["active", "paused", "cancelled", "trial", "lapsed"])
    val status: String? = null,
    val renewsOn: LocalDate? = null,
    @Schema(description = "Set true to remove an existing renewal date.")
    val clearRenewsOn: Boolean = false,
    val autoRenews: Boolean? = null,
    val cannotCancel: Boolean? = null,
    @field:Size(max = 2_000)
    val notes: String? = null,
    @Schema(description = "Set true to remove existing notes.")
    val clearNotes: Boolean = false,
)
