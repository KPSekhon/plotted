package app.plotted.identity.domain

import app.plotted.identity.persistence.UserRepository
import app.plotted.platform.audit.AuditLogRepository
import app.plotted.platform.config.PlottedProperties
import app.plotted.platform.error.EmailAlreadyRegisteredException
import app.plotted.platform.error.InvalidCredentialsException
import app.plotted.platform.error.NotFoundException
import app.plotted.platform.error.UnsupportedRegionException
import app.plotted.platform.security.AuthenticatedUser
import app.plotted.platform.security.JwtService
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

@Service
class IdentityService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenService,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder,
    private val auditLog: AuditLogRepository,
    private val properties: PlottedProperties,
) {
    /**
     * A real hash of a value nobody knows, so that verifying a non-existent
     * account costs the same as verifying a real one. Computed once, lazily; a
     * hard-coded string would not survive a change to the Argon2 parameters.
     */
    private val dummyPasswordHash: String by lazy { passwordEncoder.encode(UUID.randomUUID().toString()) }

    @Transactional
    fun register(command: RegisterCommand): AuthenticatedSession {
        val region = command.regionCode?.uppercase() ?: properties.region.default
        if (region !in properties.region.supported) {
            throw UnsupportedRegionException(region, properties.region.supported)
        }
        if (users.emailExists(command.email)) {
            throw EmailAlreadyRegisteredException()
        }

        val userId = UUID.randomUUID()
        val account =
            try {
                users.insert(
                    id = userId,
                    email = command.email,
                    passwordHash = passwordEncoder.encode(command.password),
                    displayName = command.displayName,
                    regionCode = region,
                    timezone = command.timezone ?: properties.region.defaultTimezone,
                    currency = properties.region.defaultCurrency,
                )
            } catch (_: DuplicateKeyException) {
                // Lost the race between emailExists and the insert. The unique
                // constraint is the real guard; the check above is only there to
                // produce a nicer error in the common case.
                throw EmailAlreadyRegisteredException()
            }

        users.insertDefaultSettings(userId)
        auditLog.record(
            AuditLogRepository.AuditEntry(
                actorUserId = userId,
                action = "user.registered",
                resourceType = "user",
                resourceId = userId,
                ipHash = command.client.ipAddress?.let(::sha256),
            ),
        )
        return issueSession(account, command.client)
    }

    @Transactional
    fun login(command: LoginCommand): AuthenticatedSession {
        val stored = users.findByEmail(command.email)

        // Always run a verification, even when the account does not exist, so
        // that response time does not distinguish "no such user" from "wrong
        // password". Argon2id is slow by design and that timing is measurable.
        val hash = stored?.passwordHash ?: dummyPasswordHash
        val matches = passwordEncoder.matches(command.password, hash)

        if (stored == null || !matches) {
            throw InvalidCredentialsException()
        }

        auditLog.record(
            AuditLogRepository.AuditEntry(
                actorUserId = stored.account.id,
                action = "user.logged_in",
                resourceType = "user",
                resourceId = stored.account.id,
                ipHash = command.client.ipAddress?.let(::sha256),
            ),
        )
        return issueSession(stored.account, command.client)
    }

    @Transactional
    fun refresh(presentedToken: String, client: RefreshTokenService.ClientContext): AuthenticatedSession {
        val rotation = refreshTokens.rotate(presentedToken, client)
        val account = users.findById(rotation.userId) ?: throw NotFoundException("Account")
        val accessToken = jwtService.issueAccessToken(AuthenticatedUser(account.id, account.email))
        return AuthenticatedSession(account, accessToken, rotation.refreshToken)
    }

    @Transactional
    fun logout(presentedToken: String?, actorUserId: UUID?) {
        presentedToken?.let(refreshTokens::revoke)
        actorUserId?.let {
            auditLog.record(
                AuditLogRepository.AuditEntry(
                    actorUserId = it,
                    action = "user.logged_out",
                    resourceType = "user",
                    resourceId = it,
                ),
            )
        }
    }

    fun currentAccount(userId: UUID): UserAccount = users.findById(userId) ?: throw NotFoundException("Account")

    fun settings(userId: UUID): UserSettings = users.findSettings(userId) ?: throw NotFoundException("Settings")

    @Transactional
    fun updateSettings(userId: UUID, patch: UserSettingsPatch): UserSettings {
        val before = users.findSettings(userId) ?: throw NotFoundException("Settings")
        users.updateSettings(userId, patch)
        val after = users.findSettings(userId) ?: throw NotFoundException("Settings")
        auditLog.record(
            AuditLogRepository.AuditEntry(
                actorUserId = userId,
                action = "user.settings_updated",
                resourceType = "user_settings",
                resourceId = userId,
                beforeState = mapOf("budget" to before.maximumMonthlyBudget, "services" to before.maximumActiveServices),
                afterState = mapOf("budget" to after.maximumMonthlyBudget, "services" to after.maximumActiveServices),
            ),
        )
        return after
    }

    private fun issueSession(account: UserAccount, client: RefreshTokenService.ClientContext): AuthenticatedSession {
        val accessToken = jwtService.issueAccessToken(AuthenticatedUser(account.id, account.email))
        val refreshToken = refreshTokens.issueNewFamily(account.id, client)
        return AuthenticatedSession(account, accessToken, refreshToken)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    data class RegisterCommand(
        val email: String,
        val password: String,
        val displayName: String,
        val regionCode: String?,
        val timezone: String?,
        val client: RefreshTokenService.ClientContext,
    )

    data class LoginCommand(
        val email: String,
        val password: String,
        val client: RefreshTokenService.ClientContext,
    )

    data class AuthenticatedSession(
        val account: UserAccount,
        val accessToken: JwtService.IssuedToken,
        val refreshToken: String,
    )
}
