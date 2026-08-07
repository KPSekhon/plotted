package app.plotted.demo.api

import app.plotted.demo.domain.DemoService
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(
    description =
    "A demo session. Shaped like the normal session response so the client's sign-in path " +
        "does not need a second branch, with the demo-specific facts alongside it.",
)
data class DemoSessionResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Instant,
    val userId: UUID,
    val displayName: String,
    @Schema(description = "How many titles the demo persona's list was built with.")
    val watchlistSize: Int,
    @Schema(description = "Services the persona pays for, chosen from what actually covers their list.")
    val subscriptions: List<String>,
    @Schema(
        description =
        "True when the catalogue has not been seeded, so the demo has no titles to work with. " +
            "Reported rather than hidden: two empty screens caused by a missing catalogue look " +
            "exactly like two broken features, and the difference matters to whoever is looking.",
    )
    val catalogueIsEmpty: Boolean,
) {
    companion object {
        fun from(demo: DemoService.DemoSession) = DemoSessionResponse(
            accessToken = demo.session.accessToken,
            tokenType = "Bearer",
            expiresAt = demo.session.accessTokenExpiresAt,
            userId = demo.userId,
            displayName = demo.displayName,
            watchlistSize = demo.watchlistSize,
            subscriptions = demo.subscriptions,
            catalogueIsEmpty = demo.catalogueIsEmpty,
        )
    }
}
