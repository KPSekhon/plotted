package app.plotted.support

import org.testcontainers.DockerClientFactory

/**
 * Integration tests need a container runtime. CI always has one; a laptop may
 * not. Rather than failing the whole build where Docker is absent, the
 * container-backed tests are skipped and the unit tests still run -- and CI is
 * the gate that guarantees they were actually executed before merge.
 */
object DockerSupport {
    @JvmStatic
    fun isDockerAvailable(): Boolean = try {
        DockerClientFactory.instance().isDockerAvailable
    } catch (_: Throwable) {
        false
    }
}
