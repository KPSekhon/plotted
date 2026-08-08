package app.plotted.platform

import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * Every `application*.yml` parses, the way Spring parses it.
 *
 * This exists because a duplicate `management:` key shipped and reached a
 * developer's console as an unrecoverable startup failure. The whole suite was
 * green when it was committed, and that was not luck -- it was structural:
 *
 *   * nothing that runs without Docker loads a profile YAML at all, and
 *   * everything that does load one is `@EnabledIf(isDockerAvailable)`.
 *
 * So the file that decides whether the application can start was checked only on
 * a machine that had Docker, which is not the machine anyone was starting it on.
 *
 * `YamlPropertySourceLoader` is the same loader Spring Boot uses, with the same
 * strict constructor, so a duplicate key throws here exactly as it would at
 * boot. No Spring context, no database, no container -- which is the entire
 * point: this has to run where the failure happens.
 */
class ApplicationYamlTest {
    @Test
    fun `every application yaml loads`() {
        val loader = YamlPropertySourceLoader()

        PROFILES.forEach { name ->
            val resource = ClassPathResource(name)
            check(resource.exists()) { "$name is missing from the classpath" }

            // Throws DuplicateKeyException on a repeated mapping key, which is
            // the failure this test was written for. Anything malformed throws
            // here too.
            val sources = loader.load(name, resource)
            sources.shouldNotBeEmpty()
        }
    }

    private companion object {
        /**
         * Listed rather than globbed.
         *
         * A glob over the classpath would silently pass if a file went missing or
         * was never packaged, which is the same shape of hole this test exists to
         * close. Adding a profile means adding it here, deliberately.
         */
        val PROFILES = listOf(
            "application.yml",
            "application-dev.yml",
            "application-prod.yml",
        )
    }
}
