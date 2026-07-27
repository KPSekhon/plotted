package app.plotted.platform.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The OpenAPI document is the contract. It is generated from the code, committed
 * to the repository, checked for drift in CI, and used to generate the Angular
 * client -- which is why there is no hand-written HTTP client in plotted-web.
 */
@Configuration
class OpenApiConfig {
    @Bean
    fun plottedOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Plotted API")
                .version("v1")
                .description(
                    "Platform-neutral streaming decision and subscription optimisation platform. " +
                        "Title metadata and regional availability originate from TMDB and JustWatch; " +
                        "see /legal/data-sources for attribution and refresh cadence.",
                )
                .license(License().name("AGPL-3.0-or-later")),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_SCHEME,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Short-lived access token issued by /api/v1/auth/login."),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

    private companion object {
        const val BEARER_SCHEME = "bearerAuth"
    }
}
