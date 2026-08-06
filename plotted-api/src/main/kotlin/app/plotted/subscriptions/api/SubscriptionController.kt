package app.plotted.subscriptions.api

import app.plotted.platform.error.ApiException
import app.plotted.platform.error.ErrorCode
import app.plotted.platform.security.currentUser
import app.plotted.subscriptions.domain.BillingPeriod
import app.plotted.subscriptions.domain.SubscriptionService
import app.plotted.subscriptions.domain.SubscriptionStatus
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/subscriptions")
class SubscriptionController(
    private val subscriptions: SubscriptionService,
) {
    @GetMapping
    @Operation(
        summary = "What the signed-in user pays for",
        description =
        "monthlyTotal counts active and trial subscriptions only. Cancelled and lapsed rows " +
            "are kept and returned, because what someone used to pay for is part of the record, " +
            "but they cost nothing now and including them would overstate the bill.",
    )
    fun list(): ResponseEntity<SubscriptionListResponse> =
        ResponseEntity.ok(SubscriptionListResponse.from(subscriptions.list(currentUser().userId)))

    @PostMapping
    @Operation(summary = "Record a subscription")
    fun add(@Valid @RequestBody request: CreateSubscriptionRequest): ResponseEntity<SubscriptionResponse> {
        val subscription = subscriptions.add(
            userId = currentUser().userId,
            request = SubscriptionService.NewSubscription(
                providerId = requireNotNull(request.providerId),
                planName = request.planName.orEmpty(),
                billingPeriod = parseBillingPeriod(request.billingPeriod),
                price = requireNotNull(request.price),
                currency = request.currency?.uppercase() ?: DEFAULT_CURRENCY,
                status = parseStatus(request.status),
                startedOn = request.startedOn,
                renewsOn = request.renewsOn,
                commitmentEndsOn = request.commitmentEndsOn,
                autoRenews = request.autoRenews,
                cannotCancel = request.cannotCancel,
                notes = request.notes,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionResponse.from(subscription))
    }

    @PatchMapping("/{subscriptionId}")
    @Operation(
        summary = "Update a subscription",
        description = "Partial update; omitted fields are unchanged.",
    )
    fun update(
        @PathVariable subscriptionId: UUID,
        @Valid @RequestBody request: UpdateSubscriptionRequest,
    ): ResponseEntity<SubscriptionResponse> {
        val subscription = subscriptions.update(
            userId = currentUser().userId,
            subscriptionId = subscriptionId,
            patch = SubscriptionService.SubscriptionPatch(
                status = request.status?.let(::parseStatus),
                renewsOn = request.renewsOn,
                clearRenewsOn = request.clearRenewsOn,
                autoRenews = request.autoRenews,
                cannotCancel = request.cannotCancel,
                notes = request.notes,
                clearNotes = request.clearNotes,
            ),
        )
        return ResponseEntity.ok(SubscriptionResponse.from(subscription))
    }

    @DeleteMapping("/{subscriptionId}")
    @Operation(
        summary = "Forget a subscription entirely",
        description =
        "Deletes the record. To mark something as no longer paid for while keeping its " +
            "history, PATCH the status to cancelled instead.",
    )
    fun remove(@PathVariable subscriptionId: UUID): ResponseEntity<Void> {
        subscriptions.remove(currentUser().userId, subscriptionId)
        return ResponseEntity.noContent().build()
    }

    // Parsed rather than bound as enums so an unknown value produces a 400 that
    // names the field and lists what is accepted, instead of a generic complaint
    // about unreadable JSON.
    private fun parseBillingPeriod(value: String?): BillingPeriod {
        if (value == null) return BillingPeriod.MONTHLY
        return BillingPeriod.parse(value) ?: throw ApiException(
            ErrorCode.VALIDATION_FAILED,
            "Unknown billing period '$value'",
            mapOf("billingPeriod" to "Must be one of: ${BillingPeriod.entries.joinToString(", ") { it.dbValue }}"),
        )
    }

    private fun parseStatus(value: String?): SubscriptionStatus {
        if (value == null) return SubscriptionStatus.ACTIVE
        return SubscriptionStatus.parse(value) ?: throw ApiException(
            ErrorCode.VALIDATION_FAILED,
            "Unknown subscription status '$value'",
            mapOf("status" to "Must be one of: ${SubscriptionStatus.entries.joinToString(", ") { it.dbValue }}"),
        )
    }

    private companion object {
        const val DEFAULT_CURRENCY = "CAD"
    }
}
