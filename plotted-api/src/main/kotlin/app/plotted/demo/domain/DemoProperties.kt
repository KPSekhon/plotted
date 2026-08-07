package app.plotted.demo.domain

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Demo mode: a signed-in Plotted with no signup.
 *
 * **Off by default, and that is not caution for its own sake.** The endpoint
 * creates database rows for anyone who can reach it. On a deployment that is
 * meant to be a demo that is the entire point; on any other one it is an
 * unauthenticated write, so the default has to be the safe answer and the demo
 * deployment has to say so out loud.
 */
@ConfigurationProperties(prefix = "plotted.demo")
data class DemoProperties(
    val enabled: Boolean = false,
    /**
     * How long a demo account lives. Long enough that someone can come back to
     * the tab after lunch, short enough that a day's traffic does not accumulate
     * into a table nobody clears.
     */
    val accountLifetime: Duration = Duration.ofHours(24),
    /**
     * A ceiling on live demo accounts, because this is an unauthenticated
     * endpoint that writes. Past it the endpoint refuses rather than degrading:
     * a demo that is briefly unavailable is recoverable, and a free-tier
     * database filled by a script is not.
     */
    val maximumLiveAccounts: Int = 500,
    /** How many titles the demo persona has on their list. */
    val watchlistSize: Int = 12,
)
