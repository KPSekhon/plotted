package app.plotted.platform.config

import app.plotted.platform.error.ErrorCode
import app.plotted.platform.security.JwtAuthenticationFilter
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.net.URI

const val DEVELOPMENT_JWT_SECRET = "ZGV2ZWxvcG1lbnQtb25seS1zZWNyZXQtZG8tbm90LXVzZS1pbi1wcm9k"

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val properties: PlottedProperties,
    private val environment: Environment,
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val objectMapper: ObjectMapper,
) {
    /**
     * A checked-in development secret is convenient and, left in place, is how
     * portfolio projects end up with a forgeable token in production. Fail loudly
     * instead of relying on someone remembering.
     */
    @PostConstruct
    fun rejectDevelopmentSecretOutsideDev() {
        val isDev = environment.activeProfiles.isEmpty() || environment.activeProfiles.contains("dev")
        check(isDev || properties.security.jwt.secret != DEVELOPMENT_JWT_SECRET) {
            "The development JWT secret is in use outside the dev profile. Set PLOTTED_JWT_SECRET."
        }
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = with(properties.security.argon2) {
        Argon2PasswordEncoder(saltLength, hashLength, parallelism, memoryKb, iterations)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() } // Stateless bearer-token API; no cookie-borne ambient authority.
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .headers { headers ->
                headers.frameOptions { it.deny() }
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
            }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        // Logout authenticates on the refresh cookie, not the
                        // access token. Requiring a live access token would mean
                        // an expired session could not revoke itself, which is
                        // exactly the session most worth revoking.
                        "/api/v1/auth/logout",
                    ).permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                    ).permitAll()
                    .requestMatchers(
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { handling ->
                handling.authenticationEntryPoint { _, response, _ ->
                    writeProblem(response, ErrorCode.AUTHENTICATION_REQUIRED, "This endpoint requires authentication")
                }
                handling.accessDeniedHandler { _, response, _ ->
                    writeProblem(response, ErrorCode.FORBIDDEN, "You do not have access to this resource")
                }
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Idempotency-Key", "If-None-Match")
            exposedHeaders = listOf("ETag", "Retry-After")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", configuration)
        }
    }

    /**
     * Authentication failures are rejected by the filter chain, which runs before
     * the controller advice, so the Problem Detail has to be written here too.
     */
    private fun writeProblem(response: jakarta.servlet.http.HttpServletResponse, code: ErrorCode, detail: String) {
        val problem = ProblemDetail.forStatus(code.status).apply {
            type = URI.create(code.type)
            title = code.title
            this.detail = detail
            setProperty("code", code.name)
        }
        response.status = code.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(response.outputStream, problem)
    }
}
