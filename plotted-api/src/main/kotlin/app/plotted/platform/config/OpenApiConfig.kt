package app.plotted.platform.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
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
        // Pinned, because left alone springdoc fills this in from the request it
        // happened to be serving. Under @SpringBootTest(RANDOM_PORT) that is a
        // different ephemeral port on every run, so the committed document could
        // never match the regenerated one and the drift check could never pass
        // -- it only looked green while the file was absent and the test took
        // its write-and-return branch. It would also bake a dead localhost port
        // into the generated Angular client.
        //
        // A relative URL is the honest answer anyway: the web app is served from
        // the same origin in production and proxies /api in development, so the
        // API is always exactly here.
        .servers(listOf(Server().url("/").description("This deployment")))
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
