package io.github.sehako.japda.auth

import io.github.sehako.japda.auth.domain.repository.PrincipalIdentityRepository
import java.util.UUID
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

@SpringBootTest(properties = ["product.image.s3.region=ap-northeast-2", "product.image.s3.bucket=test-product-images"])
@Testcontainers
@DisplayName("인증 주체와 도메인 ID 연결 저장")
class PrincipalIdentityPersistenceIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var identities: PrincipalIdentityRepository

    @Test
    @DisplayName("검증된 구매자·판매자 연결을 조회하며 동일한 도메인 ID의 중복 연결을 거부한다")
    fun 검증된_연결_조회와_중복_거부() {
        val first = createUser()
        val second = createUser()
        jdbc.update("INSERT INTO buyer_principal_identities (user_id, buyer_id) VALUES (?, ?)", first, 700L)
        assertEquals(700L, identities.findBuyerId(first))
        assertFails { jdbc.update("INSERT INTO buyer_principal_identities (user_id, buyer_id) VALUES (?, ?)", second, 700L) }
        assertEquals(null, identities.findBuyerId(second))
        jdbc.update("DELETE FROM buyer_principal_identities WHERE user_id = ?", first)
        jdbc.update("INSERT INTO seller_principal_identities (user_id, seller_id) VALUES (?, ?)", first, 800L)
        assertEquals(800L, identities.findSellerId(first))
        assertFails { jdbc.update("INSERT INTO seller_principal_identities (user_id, seller_id) VALUES (?, ?)", second, 800L) }
        assertEquals(null, identities.findSellerId(second))
    }

    @Test
    @DisplayName("신규 구매자 ID는 기존 연결과 과거 주문 및 배송지 ID를 넘어서 발급한다")
    fun 신규_구매자_ID_기존_ID_초과() {
        val userId = createUser()
        val priorMaximum = jdbc.queryForObject("SELECT GREATEST(COALESCE((SELECT MAX(buyer_id) FROM orders), 0), COALESCE((SELECT MAX(buyer_id) FROM buyer_shipping_address_books), 0), COALESCE((SELECT MAX(buyer_id) FROM buyer_principal_identities), 0))", Long::class.java)!!
        jdbc.queryForObject("SELECT setval('buyer_domain_id_seq', GREATEST(?, (SELECT last_value FROM buyer_domain_id_seq)) + 1, false)", Long::class.java, priorMaximum)
        val issued = identities.createBuyerLink(userId)
        assertTrue(issued > priorMaximum)
        assertEquals(issued, identities.findBuyerId(userId))
    }

    private fun createUser(): Long = jdbc.queryForObject(
        "INSERT INTO users (provider, provider_subject, email, created_at) VALUES ('GOOGLE', ?, ?, now()) RETURNING id",
        Long::class.java, UUID.randomUUID().toString(), "user-${UUID.randomUUID()}@example.com",
    )!!

    companion object {
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
