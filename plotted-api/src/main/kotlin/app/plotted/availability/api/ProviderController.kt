package app.plotted.availability.api

import app.plotted.availability.domain.ProviderCatalogueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The provider list, served by the module that owns providers.
 *
 * The subscriptions screen needs it to offer a picker. It lives here rather than
 * there because `providers` is availability's table, and a second module
 * exposing the same rows would be two answers to one question.
 */
@RestController
@RequestMapping("/api/v1/providers")
class ProviderController(
    private val providers: ProviderCatalogueService,
) {
    @GetMapping
    @Operation(
        summary = "Services a user could be subscribed to",
        description =
        "Filtered to providers that bill on a recurring basis, so a rental storefront does " +
            "not appear on a screen asking what someone subscribes to. Carries no pricing: " +
            "Plotted ships none, because a price it invented would reach the optimiser.",
    )
    fun list(): ResponseEntity<ProviderListResponse> = ResponseEntity.ok(
        ProviderListResponse(
            providers = providers.subscribable().map {
                ProviderResponse(
                    id = it.provider.id,
                    name = it.provider.name,
                    slug = it.provider.slug,
                    type = it.provider.type.dbValue,
                    logoUrl = it.logoUrl,
                )
            },
        ),
    )
}

data class ProviderListResponse(
    val providers: List<ProviderResponse>,
)

@Schema(description = "A streaming service. No price: the user supplies what they pay.")
data class ProviderResponse(
    val id: UUID,
    val name: String,
    val slug: String,
    val type: String,
    val logoUrl: String?,
)
