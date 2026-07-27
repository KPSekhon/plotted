package app.plotted.platform

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The OpenAPI document is generated from the code, committed, and used to
 * generate the Angular client. A committed specification that has drifted from
 * the implementation is worse than none at all, so this test is the check: it
 * regenerates the document and fails when it differs from the committed copy.
 *
 * This replaces consumer-driven contract testing (Pact). Pact solves
 * coordination between independently deployed services; the Angular client and
 * this API live in one repository and deploy together, so the drift check buys
 * nearly the same signal for a fraction of the effort. See
 * docs/adr/0005-openapi-client-over-pact.md.
 *
 * Regenerate after an intentional API change:
 *     ./gradlew :plotted-api:test --tests '*OpenApiContractTest*' -Dplotted.openapi.write=true
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("app.plotted.support.DockerSupport#isDockerAvailable")
class OpenApiContractTest {
    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `the committed OpenAPI document matches the running API`() {
        val document = rest.getForObject("/v3/api-docs", String::class.java)
        val formatted = objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(objectMapper.readTree(document))
            .replace("\r\n", "\n") + "\n"

        val committed = Path.of("..", "openapi", "openapi.json")

        if (!committed.exists() || System.getProperty("plotted.openapi.write") == "true") {
            Files.createDirectories(committed.parent)
            Files.writeString(committed, formatted)
            log.warn("Wrote OpenAPI document to {}. Commit it.", committed.toAbsolutePath().normalize())
            return
        }

        val onDisk = committed.readText().replace("\r\n", "\n")
        if (onDisk != formatted) {
            Files.writeString(Path.of("build", "openapi-actual.json"), formatted)
        }
        withClue(committed) { onDisk shouldBe formatted }
    }

    private fun withClue(path: Path, block: () -> Unit) {
        try {
            block()
        } catch (failure: AssertionError) {
            throw AssertionError(
                "The committed OpenAPI document at $path no longer matches the API. " +
                    "The regenerated document was written to build/openapi-actual.json. " +
                    "If the API change was intentional, rerun with -Dplotted.openapi.write=true and commit the result.",
                failure,
            )
        }
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("plotted")
                .withUsername("plotted")
                .withPassword("plotted")
    }
}
