package app.plotted

import app.plotted.platform.config.PlottedProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(PlottedProperties::class)
class PlottedApplication

fun main(args: Array<String>) {
    runApplication<PlottedApplication>(*args)
}
