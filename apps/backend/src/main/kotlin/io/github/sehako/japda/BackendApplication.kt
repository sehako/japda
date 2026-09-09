package io.github.sehako.japda

import java.time.Clock
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class BackendApplication {

	@Bean
	fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
	runApplication<BackendApplication>(*args)
}
