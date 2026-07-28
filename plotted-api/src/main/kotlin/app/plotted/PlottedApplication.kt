package app.plotted

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PlottedApplication

fun main(args: Array<String>) {
    runApplication<PlottedApplication>(*args)
}
