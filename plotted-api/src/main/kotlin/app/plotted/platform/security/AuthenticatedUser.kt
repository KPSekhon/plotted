package app.plotted.platform.security

import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * The authenticated principal. Deliberately minimal: it carries identity only,
 * never viewing data, so that nothing downstream is tempted to read personal
 * information off the security context instead of going through the owning module.
 */
data class AuthenticatedUser(
    val userId: UUID,
    val email: String,
)

class PlottedAuthentication(
    val user: AuthenticatedUser,
) : Authentication {
    private var authenticated = true

    override fun getName(): String = user.email

    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()

    override fun getCredentials(): Any? = null

    override fun getDetails(): Any = user

    override fun getPrincipal(): Any = user

    override fun isAuthenticated(): Boolean = authenticated

    override fun setAuthenticated(isAuthenticated: Boolean) {
        authenticated = isAuthenticated
    }
}

/**
 * Resolves the caller, or throws if there is none. Controllers use this rather
 * than accepting a user id as a parameter, which is the usual way an authorisation
 * bug gets introduced.
 */
fun currentUser(): AuthenticatedUser {
    val authentication = SecurityContextHolder.getContext().authentication
    return (authentication as? PlottedAuthentication)?.user
        ?: throw app.plotted.platform.error.ApiException(
            app.plotted.platform.error.ErrorCode.AUTHENTICATION_REQUIRED,
            "This endpoint requires authentication",
        )
}
