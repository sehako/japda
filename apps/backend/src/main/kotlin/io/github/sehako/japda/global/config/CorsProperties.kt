package io.github.sehako.japda.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("cors")
data class CorsProperties(
	val allowedOrigins: List<String>,
)
