package app.plotted.identity.persistence

import app.plotted.generated.jooq.tables.references.REFRESH_TOKENS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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

    /**
     * Revokes a family in a transaction of its own.
     *
     * Reuse detection revokes the family and then rejects the request, and the
     * rejection is a [RuntimeException] -- so the surrounding transaction rolls
     * back and takes the revocation with it. The caller still receives its 401,
     * which is what makes the failure invisible: the family survives, whoever
     * holds the stolen successor goes on using it, and the entire mechanism is
     * inert while every unit test still passes, because a mock records the call
     * that the database then discards.
     *
     * `REQUIRES_NEW` rather than `noRollbackFor` because rotation also runs
     * inside `IdentityService.refresh`'s transaction. Suppressing the rollback
     * would have to be done at every enclosing level and stay done as callers
     * change; committing the revocation on its own does not care what encloses
     * it.
     *
     * Both callers reach this having changed no row in `refresh_tokens`: the
     * reuse branch has only read, and the lost-race branch ran a [markUsed]
     * whose `WHERE` matched nothing and so locked nothing. The suspended outer
     * transaction therefore holds no row lock this one could block on, and the
     * two cannot deadlock against each other.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revokeFamilyIndependently(familyId: UUID, reason: String): Int = revokeFamily(familyId, reason)

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
