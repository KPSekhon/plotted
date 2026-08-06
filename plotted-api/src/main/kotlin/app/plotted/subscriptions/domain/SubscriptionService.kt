package app.plotted.subscriptions.domain

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.error.NotFoundException
import app.plotted.platform.integration.tmdb.TmdbProperties
import app.plotted.subscriptions.persistence.SubscriptionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * What the user pays for, and what it costs them.
 *
 * This is the input side of Cancel Culture. Phase 5 searches over which of these
 * to keep; phase 3's job is to make sure the numbers it searches over are real
 * ones the user typed rather than figures Plotted guessed.
 */
@Service
class SubscriptionService(
    private val subscriptions: SubscriptionRepository,
    private val properties: TmdbProperties,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(userId: UUID): SubscriptionSummary {
        val all = subscriptions.findForUser(userId)
        val current = all.filter { it.status.isCurrent }
        return SubscriptionSummary(
            subscriptions = all,
            monthlyTotal = current
                .fold(BigDecimal.ZERO) { total, subscription -> total + subscription.monthlyCost }
                .setScale(2, RoundingMode.HALF_UP),
            currency = current.firstOrNull()?.currency ?: DEFAULT_CURRENCY,
            // Cancelled and lapsed rows are kept and shown, but they are not
            // costing anything, so folding them into the total would overstate
            // the bill the optimiser is trying to reduce.
            countedSubscriptions = current.size,
        )
    }

    @Transactional
    fun add(userId: UUID, request: NewSubscription): Subscription {
        if (!subscriptions.providerExists(request.providerId)) {
            throw NotFoundException("Provider")
        }
        if (request.price < BigDecimal.ZERO) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Price cannot be negative",
                mapOf("price" to "Must be zero or more"),
            )
        }

        val planId = subscriptions.findOrCreatePlan(
            providerId = request.providerId,
            regionCode = properties.region,
            planName = request.planName.trim().ifBlank { DEFAULT_PLAN_NAME },
            billingPeriod = request.billingPeriod,
            price = request.price,
            currency = request.currency,
        )

        val id = subscriptions.insert(
            userId = userId,
            providerPlanId = planId,
            price = request.price,
            currency = request.currency,
            status = request.status,
            startedOn = request.startedOn ?: LocalDate.now(clock),
            renewsOn = request.renewsOn,
            commitmentEndsOn = request.commitmentEndsOn,
            autoRenews = request.autoRenews,
            // A commitment end date in the future is itself the evidence that
            // this cannot be cancelled yet, so the flag is derived rather than
            // asked for twice and allowed to disagree with itself.
            cannotCancel = request.cannotCancel || (request.commitmentEndsOn?.isAfter(LocalDate.now(clock)) ?: false),
            notes = request.notes,
        )
        return subscriptions.findOne(userId, id) ?: error("Subscription $id vanished between insert and read")
    }

    @Transactional
    fun update(userId: UUID, subscriptionId: UUID, patch: SubscriptionPatch): Subscription {
        val changed = subscriptions.update(
            userId = userId,
            subscriptionId = subscriptionId,
            status = patch.status,
            renewsOn = patch.renewsOn,
            clearRenewsOn = patch.clearRenewsOn,
            autoRenews = patch.autoRenews,
            cannotCancel = patch.cannotCancel,
            notes = patch.notes,
            clearNotes = patch.clearNotes,
        )
        if (!changed) throw NotFoundException("Subscription")
        return subscriptions.findOne(userId, subscriptionId) ?: throw NotFoundException("Subscription")
    }

    @Transactional
    fun remove(userId: UUID, subscriptionId: UUID) {
        if (!subscriptions.delete(userId, subscriptionId)) {
            throw NotFoundException("Subscription")
        }
    }

    data class SubscriptionSummary(
        val subscriptions: List<Subscription>,
        val monthlyTotal: BigDecimal,
        val currency: String,
        val countedSubscriptions: Int,
    )

    data class NewSubscription(
        val providerId: UUID,
        val planName: String,
        val billingPeriod: BillingPeriod,
        val price: BigDecimal,
        val currency: String,
        val status: SubscriptionStatus,
        val startedOn: LocalDate?,
        val renewsOn: LocalDate?,
        val commitmentEndsOn: LocalDate?,
        val autoRenews: Boolean,
        val cannotCancel: Boolean,
        val notes: String?,
    )

    data class SubscriptionPatch(
        val status: SubscriptionStatus? = null,
        val renewsOn: LocalDate? = null,
        val clearRenewsOn: Boolean = false,
        val autoRenews: Boolean? = null,
        val cannotCancel: Boolean? = null,
        val notes: String? = null,
        val clearNotes: Boolean = false,
    )

    private companion object {
        const val DEFAULT_CURRENCY = "CAD"
        const val DEFAULT_PLAN_NAME = "Standard"
    }
}
