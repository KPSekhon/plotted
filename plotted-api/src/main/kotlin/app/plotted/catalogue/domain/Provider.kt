package app.plotted.catalogue.domain

import java.util.UUID

enum class ProviderType(
    val dbValue: String,
) {
    SUBSCRIPTION("subscription"),
    FREE("free"),
    TRANSACTIONAL("transactional"),
    LIBRARY("library"),
    LINEAR("linear"),
    ;

    companion object {
        fun fromDb(value: String): ProviderType = entries.firstOrNull { it.dbValue == value } ?: error("Unknown provider_type '$value'")
    }
}

/** How a title is reached. Mirrors `title_availability.access_type`. */
enum class AccessType(
    val dbValue: String,
) {
    SUBSCRIPTION("subscription"),
    FREE("free"),
    ADS("ads"),
    RENT("rent"),
    BUY("buy"),
    LIBRARY("library"),
    ;

    /**
     * Whether this is a way of watching something already paid for, as opposed
     * to a separate transaction. Only these compete with one another for the
     * same provider.
     */
    val isIncluded: Boolean get() = this == SUBSCRIPTION || this == FREE || this == ADS

    companion object {
        fun fromDb(value: String): AccessType = entries.firstOrNull { it.dbValue == value } ?: error("Unknown access_type '$value'")
    }
}

/** Why one TMDB provider maps onto another Plotted provider. */
enum class AliasKind(
    val dbValue: String,
) {
    /** The service itself. */
    DIRECT("direct"),

    /** Bought through Amazon or Apple; same catalogue, different bill. */
    RESELLER_CHANNEL("reseller_channel"),

    /** A cheaper advertising-supported tier of the same service. */
    AD_TIER("ad_tier"),

    /** Another billing tier, such as Paramount Plus Premium. */
    PLAN_TIER("plan_tier"),

    /** A filtered view, such as Netflix Kids. */
    PROFILE("profile"),
    ;

    companion object {
        fun fromDb(value: String): AliasKind = entries.firstOrNull { it.dbValue == value } ?: error("Unknown alias_kind '$value'")
    }
}

data class Provider(
    val id: UUID,
    val name: String,
    val slug: String,
    val type: ProviderType,
)

/** A provider as TMDB reported it, before canonicalisation. */
data class RawProviderOffer(
    val tmdbProviderId: Int,
    val providerName: String,
    val accessType: AccessType,
)

/** A way of watching a title, on a service a user can actually subscribe to. */
data class ProviderOffer(
    val provider: Provider,
    val accessType: AccessType,
    /**
     * The TMDB provider this came from. Retained so an availability row can be
     * traced back to the exact upstream entry that produced it -- section 5
     * requires every displayed fact to carry provenance.
     */
    val sourceTmdbProviderId: Int,
    val sourceName: String,
)

/**
 * The outcome of canonicalising one title's providers.
 *
 * [unmapped] is deliberately part of the result rather than a log line. TMDB adds
 * services regularly, and a mapping gap silently removes real availability --
 * which shows up as a title Plotted thinks nobody carries. Surfacing the gap is
 * how it gets closed.
 */
data class ProviderResolution(
    val offers: List<ProviderOffer>,
    val unmapped: List<RawProviderOffer>,
) {
    val hasGaps: Boolean get() = unmapped.isNotEmpty()
}
