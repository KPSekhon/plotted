package app.plotted.availability.persistence

import app.plotted.availability.domain.Provider
import app.plotted.availability.domain.ProviderListing
import app.plotted.availability.domain.ProviderType
import app.plotted.generated.jooq.tables.references.PROVIDERS
import app.plotted.generated.jooq.tables.references.PROVIDER_ALIASES
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProviderRepository(
    private val dsl: DSLContext,
) {
    /**
     * The whole TMDB-to-Plotted mapping, as one query.
     *
     * Loading it wholesale rather than looking up ids one at a time is
     * deliberate: it is a few dozen rows that change only when a migration adds
     * to them, and ingestion resolves providers for every title it touches. A
     * per-title round trip would be the obvious source of a slow refresh.
     */
    fun loadAliasMap(): Map<Int, Provider> = dsl.select(
        PROVIDER_ALIASES.TMDB_PROVIDER_ID,
        PROVIDERS.ID,
        PROVIDERS.NAME,
        PROVIDERS.SLUG,
        PROVIDERS.PROVIDER_TYPE,
    )
        .from(PROVIDER_ALIASES)
        .join(PROVIDERS).on(PROVIDERS.ID.eq(PROVIDER_ALIASES.PROVIDER_ID))
        .where(PROVIDERS.ACTIVE.isTrue)
        .fetch()
        .associate { record ->
            record[PROVIDER_ALIASES.TMDB_PROVIDER_ID]!! to
                Provider(
                    id = record[PROVIDERS.ID]!!,
                    name = record[PROVIDERS.NAME]!!,
                    slug = record[PROVIDERS.SLUG]!!,
                    type = ProviderType.fromDb(record[PROVIDERS.PROVIDER_TYPE]!!),
                )
        }

    fun findBySlug(slug: String): Provider? = dsl.select(PROVIDERS.ID, PROVIDERS.NAME, PROVIDERS.SLUG, PROVIDERS.PROVIDER_TYPE)
        .from(PROVIDERS)
        .where(PROVIDERS.SLUG.eq(slug))
        .fetchOne()
        ?.let {
            Provider(
                id = it[PROVIDERS.ID]!!,
                name = it[PROVIDERS.NAME]!!,
                slug = it[PROVIDERS.SLUG]!!,
                type = ProviderType.fromDb(it[PROVIDERS.PROVIDER_TYPE]!!),
            )
        }

    /**
     * Active providers someone could plausibly be paying for, alphabetically.
     *
     * See [SUBSCRIBABLE_TYPES] for which types qualify and why. The short of it
     * is that a rental storefront is not something with a renewal date to
     * cancel, so offering it on a "what do you subscribe to?" screen would
     * invite the user to record something the optimiser cannot act on.
     */
    fun findSubscribable(): List<ProviderListing> = dsl.select(
        PROVIDERS.ID,
        PROVIDERS.NAME,
        PROVIDERS.SLUG,
        PROVIDERS.PROVIDER_TYPE,
        PROVIDERS.LOGO_URL,
    )
        .from(PROVIDERS)
        .where(PROVIDERS.ACTIVE.isTrue)
        .and(PROVIDERS.PROVIDER_TYPE.`in`(SUBSCRIBABLE_TYPES))
        .orderBy(PROVIDERS.NAME.asc())
        .fetch()
        .map {
            ProviderListing(
                provider = Provider(
                    id = it[PROVIDERS.ID]!!,
                    name = it[PROVIDERS.NAME]!!,
                    slug = it[PROVIDERS.SLUG]!!,
                    type = ProviderType.fromDb(it[PROVIDERS.PROVIDER_TYPE]!!),
                ),
                logoUrl = it[PROVIDERS.LOGO_URL],
            )
        }

    fun findById(providerId: UUID): Provider? = dsl.select(PROVIDERS.ID, PROVIDERS.NAME, PROVIDERS.SLUG, PROVIDERS.PROVIDER_TYPE)
        .from(PROVIDERS)
        .where(PROVIDERS.ID.eq(providerId))
        .fetchOne()
        ?.let {
            Provider(
                id = it[PROVIDERS.ID]!!,
                name = it[PROVIDERS.NAME]!!,
                slug = it[PROVIDERS.SLUG]!!,
                type = ProviderType.fromDb(it[PROVIDERS.PROVIDER_TYPE]!!),
            )
        }

    fun countAliases(): Int = dsl.fetchCount(PROVIDER_ALIASES)

    fun aliasesFor(providerId: UUID): List<Int> = dsl.select(PROVIDER_ALIASES.TMDB_PROVIDER_ID)
        .from(PROVIDER_ALIASES)
        .where(PROVIDER_ALIASES.PROVIDER_ID.eq(providerId))
        .fetch()
        .mapNotNull { it.value1() }

    private companion object {
        /**
         * Provider types a user could be paying for on a recurring basis.
         *
         * `FREE` is included because the type describes a provider's *primary*
         * model, not its only one: CBC Gem is free and also sells Gem Premium,
         * and leaving it out would make a real subscription impossible to
         * record. `TRANSACTIONAL` is excluded because renting a film is a
         * purchase rather than something with a renewal date to cancel, and
         * `LIBRARY` because a library card is not billed by the provider.
         */
        val SUBSCRIBABLE_TYPES = listOf(
            ProviderType.SUBSCRIPTION.dbValue,
            ProviderType.FREE.dbValue,
            ProviderType.LINEAR.dbValue,
        )
    }
}
