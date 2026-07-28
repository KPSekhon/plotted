package app.plotted.availability.persistence

import app.plotted.availability.domain.Provider
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

    fun countAliases(): Int = dsl.fetchCount(PROVIDER_ALIASES)

    fun aliasesFor(providerId: UUID): List<Int> = dsl.select(PROVIDER_ALIASES.TMDB_PROVIDER_ID)
        .from(PROVIDER_ALIASES)
        .where(PROVIDER_ALIASES.PROVIDER_ID.eq(providerId))
        .fetch()
        .mapNotNull { it.value1() }
}
