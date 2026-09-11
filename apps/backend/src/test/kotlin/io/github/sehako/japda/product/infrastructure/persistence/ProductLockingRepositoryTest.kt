package io.github.sehako.japda.product.infrastructure.persistence

import io.github.sehako.japda.product.domain.model.Product
import io.github.sehako.japda.product.domain.repository.ProductRepository
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("상품 잠금 영속성")
class ProductLockingRepositoryTest {
	@Autowired
	private lateinit var productRepository: ProductRepository

	@Autowired
	private lateinit var transactionTemplate: TransactionTemplate

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("한 트랜잭션이 상품을 잠그면 다른 잠금 조회는 커밋까지 대기한다")
	fun 상품_잠금_다른_잠금_조회는_커밋까지_대기한다() {
		val productId = transactionTemplate.execute {
			productRepository.save(Product.create(1L, "상품", null, Instant.parse("2026-09-10T00:00:00Z"))).id
		}!!
		val firstLocked = CountDownLatch(1)
		val releaseFirst = CountDownLatch(1)
		val secondFinished = CountDownLatch(1)
		Executors.newFixedThreadPool(2).use { executor ->
			executor.submit {
				transactionTemplate.execute {
					productRepository.findByIdForUpdate(productId)
					firstLocked.countDown()
					releaseFirst.await(5, TimeUnit.SECONDS)
				}
			}
			assertTrue(firstLocked.await(5, TimeUnit.SECONDS))
			executor.submit {
				transactionTemplate.execute { productRepository.findByIdForUpdate(productId) }
				secondFinished.countDown()
			}

			assertFalse(secondFinished.await(300, TimeUnit.MILLISECONDS))
			releaseFirst.countDown()
			assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
		}
	}

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
