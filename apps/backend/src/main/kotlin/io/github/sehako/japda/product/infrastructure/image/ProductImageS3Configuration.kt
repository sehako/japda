package io.github.sehako.japda.product.infrastructure.image

import io.github.sehako.japda.product.application.image.ProductImageObjectKeyGenerator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductImageS3Properties::class)
class ProductImageS3Configuration {

	@Bean
	fun productImageS3Client(properties: ProductImageS3Properties): S3Client = S3Client.builder()
		.region(Region.of(properties.region))
		.credentialsProvider(DefaultCredentialsProvider.builder().build())
		.build()

	@Bean
	fun productImageStorage(
		productImageS3Client: S3Client,
		properties: ProductImageS3Properties,
	): S3ProductImageStorage = S3ProductImageStorage(productImageS3Client, properties.bucket)

	@Bean
	fun productImageObjectKeyGenerator(
		properties: ProductImageS3Properties,
	): ProductImageObjectKeyGenerator = ProductImageObjectKeyGenerator(properties.prefix)
}
