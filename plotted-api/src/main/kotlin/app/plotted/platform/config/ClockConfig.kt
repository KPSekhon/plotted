package app.plotted.platform.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Time is injected everywhere rather than read from [java.time.Instant.now].
 * Token expiry, availability staleness, renewal windows and the trailing
 * eight-week viewing average are all time-dependent, and none of them are
 * testable if the clock is a static call.
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
