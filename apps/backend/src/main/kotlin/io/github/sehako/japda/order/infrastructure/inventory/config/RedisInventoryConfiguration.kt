package io.github.sehako.japda.order.infrastructure.inventory.config

import io.github.sehako.japda.order.application.inventory.SoldOutInventoryMarker
import io.github.sehako.japda.order.infrastructure.inventory.key.RedisInventoryKeyFactory
import io.github.sehako.japda.order.infrastructure.inventory.redis.DisabledSoldOutInventoryMarker
import io.github.sehako.japda.order.infrastructure.inventory.redis.RedisSoldOutInventoryMarker
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisInventoryProperties::class)
class RedisInventoryConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(SoldOutInventoryMarker::class)
    fun disabledSoldOutInventoryMarker(): SoldOutInventoryMarker = DisabledSoldOutInventoryMarker()

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
    fun inventoryRedisConnectionFactory(properties: RedisInventoryProperties): RedisConnectionFactory {
        val socketOptions = SocketOptions.builder()
            .connectTimeout(properties.connectTimeout)
            .build()
        val clientOptions = ClientOptions.builder()
            .socketOptions(socketOptions)
            .build()
        val clientConfiguration = LettuceClientConfiguration.builder()
            .commandTimeout(properties.commandTimeout)
            .clientOptions(clientOptions)
            .build()
        return LettuceConnectionFactory(
            RedisStandaloneConfiguration(properties.host, properties.port),
            clientConfiguration,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
    fun inventoryRedisTemplate(inventoryRedisConnectionFactory: RedisConnectionFactory): StringRedisTemplate =
        StringRedisTemplate(inventoryRedisConnectionFactory)

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
    fun redisInventoryKeyFactory(properties: RedisInventoryProperties): RedisInventoryKeyFactory =
        RedisInventoryKeyFactory(properties.namespace)

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(SoldOutInventoryMarker::class)
    fun redisSoldOutInventoryMarker(
        inventoryRedisTemplate: StringRedisTemplate,
        keyFactory: RedisInventoryKeyFactory,
        properties: RedisInventoryProperties,
        meterRegistry: MeterRegistry,
    ): SoldOutInventoryMarker = RedisSoldOutInventoryMarker(inventoryRedisTemplate, keyFactory, properties, meterRegistry)

    private companion object {
        const val PREFIX = "order.inventory.redis"
    }
}
