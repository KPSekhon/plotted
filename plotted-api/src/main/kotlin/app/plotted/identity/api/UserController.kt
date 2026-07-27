package app.plotted.identity.api

import app.plotted.identity.domain.IdentityService
import app.plotted.identity.domain.UserSettingsPatch
import app.plotted.platform.security.currentUser
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Note that no endpoint here accepts a user id. The caller is always taken from
 * the security context, which removes the entire class of "pass someone else's id
 * and see what happens" bug rather than defending against it per-endpoint.
 */
@RestController
@RequestMapping("/api/v1/users/me")
class UserController(
    private val identityService: IdentityService,
) {
    @GetMapping
    @Operation(summary = "The signed-in account")
    fun me(): ResponseEntity<UserResponse> = ResponseEntity.ok(UserResponse.from(identityService.currentAccount(currentUser().userId)))

    @GetMapping("/settings")
    @Operation(summary = "Recommendation and budget defaults for the signed-in account")
    fun settings(): ResponseEntity<UserSettingsResponse> =
        ResponseEntity.ok(UserSettingsResponse.from(identityService.settings(currentUser().userId)))

    @PatchMapping("/settings")
    @Operation(
        summary = "Update recommendation and budget defaults",
        description =
        "Partial update; omitted fields are unchanged. weeklyViewingMinutesOverride " +
            "replaces the trailing eight-week estimate that drives completion feasibility " +
            "and cancellation advice.",
    )
    fun updateSettings(@Valid @RequestBody request: UpdateUserSettingsRequest): ResponseEntity<UserSettingsResponse> {
        val updated = identityService.updateSettings(
            currentUser().userId,
            UserSettingsPatch(
                maximumMonthlyBudget = request.maximumMonthlyBudget,
                maximumActiveServices = request.maximumActiveServices,
                maximumMonthlySwitches = request.maximumMonthlySwitches,
                defaultAvailableMinutes = request.defaultAvailableMinutes,
                defaultAccessPolicy = request.defaultAccessPolicy,
                defaultNoveltyPreference = request.defaultNoveltyPreference,
                defaultCommitmentPreference = request.defaultCommitmentPreference,
                allowPaidRentals = request.allowPaidRentals,
                maximumRentalPrice = request.maximumRentalPrice,
                allowPhysicalMedia = request.allowPhysicalMedia,
                weeklyViewingMinutesOverride = request.weeklyViewingMinutesOverride,
            ),
        )
        return ResponseEntity.ok(UserSettingsResponse.from(updated))
    }
}
