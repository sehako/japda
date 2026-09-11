package io.github.sehako.japda.product.infrastructure.persistence

import io.github.sehako.japda.product.domain.model.Product
import io.github.sehako.japda.product.domain.repository.ProductRepository
import io.github.sehako.japda.product.domain.model.ProductStatus
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductRepositoryImpl::class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("상품 영속성")
class ProductRepositoryTest {
	@Autowired
	private lateinit var productRepository: ProductRepository

	@Autowired
	private lateinit var entityManager: EntityManager

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	@DisplayName("상품 저장 시 identity 식별자와 필드를 PostgreSQL에 영속화한다")
	fun 상품_저장_identity_식별자와_필드를_PostgreSQL에_영속화한다() {
		val createdAt = Instant.parse("2026-09-10T00:00:00Z")
		val product = Product.create(1L, "상품", null, createdAt)

		val savedProduct = productRepository.save(product)
		entityManager.flush()
		entityManager.clear()

		val foundProduct = entityManager.find(Product::class.java, assertNotNull(savedProduct.id))
		assertEquals(savedProduct.id, foundProduct.id)
		assertEquals(1L, foundProduct.sellerId)
		assertEquals("상품", foundProduct.name)
		assertNull(foundProduct.description)
		assertEquals(ProductStatus.DRAFT, foundProduct.status)
		assertEquals(createdAt, foundProduct.createdAt)
	}

	@Test
	@DisplayName("판매자 식별자가 양수가 아니면 데이터베이스가 저장을 거부한다")
	fun 판매자_식별자가_양수가_아님_데이터베이스가_저장을_거부한다() {
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"INSERT INTO products (seller_id, name, status, created_at) VALUES (?, ?, ?, ?)",
				0L,
				"상품",
				"DRAFT",
				java.time.OffsetDateTime.parse("2026-09-10T00:00:00Z"),
			)
		}
	}

	@Test
	@DisplayName("정의되지 않은 상품 상태면 데이터베이스가 저장을 거부한다")
	fun 정의되지_않은_상품_상태_데이터베이스가_저장을_거부한다() {
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"INSERT INTO products (seller_id, name, status, created_at) VALUES (?, ?, ?, ?)",
				1L,
				"상품",
				"PUBLISHED",
				java.time.OffsetDateTime.parse("2026-09-10T00:00:00Z"),
			)
		}
	}

	@Test
	@DisplayName("공백 상품명이면 데이터베이스가 저장을 거부한다")
	fun 공백_상품명_데이터베이스가_저장을_거부한다() {
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"INSERT INTO products (seller_id, name, status, created_at) VALUES (?, ?, ?, ?)",
				1L,
				"   ",
				"DRAFT",
				java.time.OffsetDateTime.parse("2026-09-10T00:00:00Z"),
			)
		}
	}

	@Test
	@DisplayName("공백 상품 설명이면 데이터베이스가 저장을 거부한다")
	fun 공백_상품_설명_데이터베이스가_저장을_거부한다() {
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"INSERT INTO products (seller_id, name, description, status, created_at) VALUES (?, ?, ?, ?, ?)",
				1L,
				"상품",
				"   ",
				"DRAFT",
				java.time.OffsetDateTime.parse("2026-09-10T00:00:00Z"),
			)
		}
	}

	companion object {
		@Container
		@ServiceConnection
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:17-alpine")
	}
}
