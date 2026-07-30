package app.plotted.platform.events

import java.util.UUID

/**
 * Events published by one module and consumed by another.
 *
 * They live in the shared kernel because that is the only place both sides can
 * see, and they carry primitives rather than domain types so that neither
 * module's model leaks into the other's. `mediaType` is a String for exactly
 * that reason: `catalogue.MediaType` is catalogue's business.
 *
 * This is how the modular monolith stays modular under a workflow that genuinely
 * spans two modules -- ingesting a title, then finding out where it streams.
 * ArchUnit forbids the direct call, and rightly: a direct call would make the
 * availability refresh part of the catalogue transaction, so a TMDB provider
 * outage would roll back a perfectly good title.
 */
data class TitleIngested(
    val titleId: UUID,
    val externalId: String,
    val mediaType: String,
    val created: Boolean,
)
