package io.github.sehako.japda.product.infrastructure.image

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
import software.amazon.awssdk.services.s3.S3Client

@DisplayName("상품 이미지 S3 설정")
class ProductImageS3ConfigurationTest {

	private val contextRunner = ApplicationContextRunner()
		.withUserConfiguration(
			ConfigurationPropertiesAutoConfiguration::class.java,
			ValidationAutoConfiguration::class.java,
			ProductImageS3Configuration::class.java,
		)

	@Test
	@DisplayName("설정 바인딩_버킷과 리전이 있으면 S3 클라이언트와 저장소를 생성한다")
	fun 설정_바인딩_버킷과_리전이_있으면_S3_클라이언트와_저장소를_생성한다() {
		contextRunner
			.withPropertyValues(
				"product.image.s3.bucket=private-product-bucket",
				"product.image.s3.region=ap-northeast-2",
				"product.image.s3.prefix=catalog-images",
			)
			.run { context ->
				assertThat(context).hasNotFailed()
				assertThat(context).hasSingleBean(S3Client::class.java)
				assertThat(context).hasSingleBean(S3ProductImageStorage::class.java)
			}
	}

	@Test
	@DisplayName("설정 바인딩_버킷이 없으면 시작 단계에서 실패한다")
	fun 설정_바인딩_버킷이_없으면_시작_단계에서_실패한다() {
		contextRunner
			.withPropertyValues("product.image.s3.region=ap-northeast-2")
			.run { context -> assertThat(context).hasFailed() }
	}

	@Test
	@DisplayName("설정 바인딩_리전이 없으면 시작 단계에서 실패한다")
	fun 설정_바인딩_리전이_없으면_시작_단계에서_실패한다() {
		contextRunner
			.withPropertyValues("product.image.s3.bucket=private-product-bucket")
			.run { context -> assertThat(context).hasFailed() }
	}
}
