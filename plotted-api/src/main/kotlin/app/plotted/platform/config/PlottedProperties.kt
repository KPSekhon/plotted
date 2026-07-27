package app.plotted.platform.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Application configuration. Everything tunable lives here rather than being read
 * from the environment at the point of use, so that the full set of knobs is
 * discoverable in one file and validated once at startup.
 */
@ConfigurationProperties(prefix = "plotted")
data class PlottedProperties(
    val security: SecurityProperties,
    val region: RegionProperties = RegionProperties(),
    val cors: CorsProperties = CorsProperties(),
) {
    data class SecurityProperties(
        val jwt: JwtProperties,
        /**
         * Argon2id parameters. Defaults follow the OWASP Password Storage Cheat
         * Sheet's second recommended configuration (19 MiB, t=2, p=1).
         */
        val argon2: Argon2Properties = Argon2Properties(),
    )

    data class JwtProperties(
        val issuer: String = "plotted",
        /**
         * HMAC signing key, base64-encoded, at least 32 bytes decoded. There is a
         * development default in application-dev.yml; [SecurityConfig] refuses to
         * start outside the dev profile if it is still in use.
         */
        val secret: String,
        val accessTokenTtl: Duration = Duration.ofMinutes(15),
        val refreshTokenTtl: Duration = Duration.ofDays(30),
        /**
         * Whether the refresh cookie carries the Secure attribute. False only for
         * plain-HTTP local development; application-prod.yml forces it true.
         */
        val refreshCookieSecure: Boolean = true,
    )

    data class Argon2Properties(
        val saltLength: Int = 16,
        val hashLength: Int = 32,
        val parallelism: Int = 1,
        val memoryKb: Int = 19456,
        val iterations: Int = 2,
    )

    data class RegionProperties(
        /** Section 1.2: Canada only at launch, deliberately. */
        val supported: Set<String> = setOf("CA"),
        val default: String = "CA",
        val defaultTimezone: String = "America/Toronto",
        val defaultCurrency: String = "CAD",
    )

    data class CorsProperties(
        val allowedOrigins: List<String> = listOf("http://localhost:4200"),
    )
}
