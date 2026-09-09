package io.github.sehako.japda.product.infrastructure.image

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("product.image.s3")
data class ProductImageS3Properties(
	@field:NotBlank
	val bucket: String,
	@field:NotBlank
	val region: String,
	val prefix: String = "product-images",
)
