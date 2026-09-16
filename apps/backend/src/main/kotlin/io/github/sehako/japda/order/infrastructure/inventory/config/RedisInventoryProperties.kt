package io.github.sehako.japda.order.infrastructure.inventory.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("order.inventory.redis")
data class RedisInventoryProperties(
    val enabled: Boolean = false,
    val namespace: String = "japda",
    val host: String = "localhost",
    val port: Int = 6379,
    val connectTimeout: Duration = Duration.ofMillis(500),
    val commandTimeout: Duration = Duration.ofMillis(200),
    val stockTtl: Duration = Duration.ofSeconds(30),
    val lockTtl: Duration = Duration.ofSeconds(3),
    val pollingTimeout: Duration = Duration.ofMillis(500),
    val pollingInterval: Duration = Duration.ofMillis(25),
)
