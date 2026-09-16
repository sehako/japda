package io.github.sehako.japda.batch.global.infrastructure.batch

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class JdbcBatchConfiguration : JdbcDefaultBatchConfiguration()
