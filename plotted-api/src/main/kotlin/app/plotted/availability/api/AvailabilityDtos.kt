package app.plotted.availability.api

import app.plotted.availability.domain.AvailabilityOffer
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant

@Schema(
    description =
    "Where a title can be watched. Every entry carries its source and when it was last " +
        "verified: availability data is imperfect, particularly for smaller Canadian " +
        "services, and a decision tool that hides that is worse than one that admits it.",
)
data class AvailabilityResponse(
    val regionCode: String,
    val offers: List<AvailabilityOfferResponse>,
    @field:Schema(description = "When the freshest entry was last checked. Null when nothing is known.")
    val lastVerifiedAt: Instant?,
    @field:Schema(
        description =
        "True when nothing here has been checked recently enough to rely on. Clients should " +
            "label the data as stale rather than hide it -- suppressing prices is the " +
            "documented degraded behaviour, not suppressing the title.",
    )
    val stale: Boolean,
    val attribution: String,
)

data class AvailabilityOfferResponse(
    val providerName: String,
    val providerSlug: String,
    @field:Schema(description = "subscription, free, transactional, library or linear.")
    val providerType: String,
    val providerLogoUrl: String?,
    @field:Schema(description = "subscription, free, ads, rent, buy or library.")
    val accessType: String,
    val price: BigDecimal?,
    val currency: String?,
    val deepLink: String?,
    val source: String,
    val verifiedAt: Instant,
    @field:Schema(description = "0 to 1. Below 1 means part of the upstream response could not be mapped.")
    val confidence: BigDecimal,
) {
    companion object {
        fun from(offer: AvailabilityOffer): AvailabilityOfferResponse = AvailabilityOfferResponse(
            providerName = offer.provider.name,
            providerSlug = offer.provider.slug,
            providerType = offer.provider.type.dbValue,
            providerLogoUrl = offer.providerLogoUrl,
            accessType = offer.accessType.dbValue,
            price = offer.price,
            currency = offer.currency,
            deepLink = offer.deepLink,
            source = offer.source,
            verifiedAt = offer.sourceCheckedAt,
            confidence = offer.confidence,
        )
    }
}
