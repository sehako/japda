package io.github.sehako.japda.product.infrastructure.image.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

@Configuration
class S3ProductImageConfiguration {
	@Bean
	fun productImageS3Client(
		@Value("\${product.image.s3.region}") region: String,
	): S3Client = S3Client.builder()
		.region(Region.of(region))
		.build()
}
