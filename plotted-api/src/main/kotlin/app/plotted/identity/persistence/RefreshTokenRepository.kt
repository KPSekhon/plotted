package app.plotted.identity.persistence

import app.plotted.generated.jooq.tables.references.REFRESH_TOKENS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class RefreshTokenRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    fun insert(id: UUID, userId: UUID, familyId: UUID, tokenHash: String, expiresAt: Instant, userAgentHash: String?, ipHash: String?) {
        dsl.insertInto(REFRESH_TOKENS)
            .set(REFRESH_TOKENS.ID, id)
            .set(REFRESH_TOKENS.USER_ID, userId)
            .set(REFRESH_TOKENS.FAMILY_ID, familyId)
            .set(REFRESH_TOKENS.TOKEN_HASH, tokenHash)
            .set(REFRESH_TOKENS.ISSUED_AT, OffsetDateTime.now(clock))
            .set(REFRESH_TOKENS.EXPIRES_AT, expiresAt.atOffset(java.time.ZoneOffset.UTC))
            .set(REFRESH_TOKENS.USER_AGENT_HASH, userAgentHash)
            .set(REFRESH_TOKENS.IP_HASH, ipHash)
            .execute()
    }

    fun findByHash(tokenHash: String): StoredRefreshToken? = dsl.selectFrom(REFRESH_TOKENS)
        .where(REFRESH_TOKENS.TOKEN_HASH.eq(tokenHash))
        .fetchOne()
        ?.let { record ->
            StoredRefreshToken(
                id = record.id!!,
                userId = record.userId!!,
                familyId = record.familyId!!,
                expiresAt = record.expiresAt!!.toInstant(),
                usedAt = record.usedAt?.toInstant(),
                revokedAt = record.revokedAt?.toInstant(),
            )
        }

    /**
     * Marks a token as spent. Returns false when it was already spent, which is
     * the signal that the token leaked and the family must be revoked.
     */
    fun markUsed(id: UUID): Boolean = dsl.update(REFRESH_TOKENS)
        .set(REFRESH_TOKENS.USED_AT, OffsetDateTime.now(clock))
        .set(REFRESH_TOKENS.REVOKED_AT, OffsetDateTime.now(clock))
        .set(REFRESH_TOKENS.REVOKED_REASON, "rotated")
        .where(REFRESH_TOKENS.ID.eq(id))
        .and(REFRESH_TOKENS.USED_AT.isNull)
        .and(REFRESH_TOKENS.REVOKED_AT.isNull)
        .execute() > 0

    fun revokeFamily(familyId: UUID, reason: String): Int = dsl.update(REFRESH_TOKENS)
        .set(REFRESH_TOKENS.REVOKED_AT, OffsetDateTime.now(clock))
        .set(REFRESH_TOKENS.REVOKED_REASON, reason)
        .where(REFRESH_TOKENS.FAMILY_ID.eq(familyId))
        .and(REFRESH_TOKENS.REVOKED_AT.isNull)
        .execute()

    fun deleteExpiredBefore(cutoff: Instant): Int = dsl.deleteFrom(REFRESH_TOKENS)
        .where(REFRESH_TOKENS.EXPIRES_AT.lt(cutoff.atOffset(java.time.ZoneOffset.UTC)))
        .execute()

    data class StoredRefreshToken(
        val id: UUID,
        val userId: UUID,
        val familyId: UUID,
        val expiresAt: Instant,
        val usedAt: Instant?,
        val revokedAt: Instant?,
    )
}
