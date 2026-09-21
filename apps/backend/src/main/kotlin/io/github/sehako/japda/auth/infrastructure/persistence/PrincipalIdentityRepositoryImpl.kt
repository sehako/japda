package io.github.sehako.japda.auth.infrastructure.persistence

import io.github.sehako.japda.auth.domain.repository.PrincipalIdentityRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PrincipalIdentityRepositoryImpl(private val jdbc: JdbcTemplate) : PrincipalIdentityRepository {
    override fun findBuyerId(userId: Long): Long? = jdbc.query(
        "SELECT buyer_id FROM buyer_principal_identities WHERE user_id = ?",
        { resultSet, _ -> resultSet.getLong("buyer_id") },
        userId,
    ).firstOrNull()

    override fun findSellerId(userId: Long): Long? = jdbc.query(
        "SELECT seller_id FROM seller_principal_identities WHERE user_id = ?",
        { resultSet, _ -> resultSet.getLong("seller_id") },
        userId,
    ).firstOrNull()

    override fun findUserIdBySellerId(sellerId: Long): Long? = jdbc.query(
        "SELECT user_id FROM seller_principal_identities WHERE seller_id = ?",
        { resultSet, _ -> resultSet.getLong("user_id") },
        sellerId,
    ).firstOrNull()

    override fun createBuyerLink(userId: Long): Long {
        val buyerId = checkNotNull(jdbc.queryForObject("SELECT nextval('buyer_domain_id_seq')", Long::class.java))
        jdbc.update("INSERT INTO buyer_principal_identities (user_id, buyer_id) VALUES (?, ?)", userId, buyerId)
        return buyerId
    }
}
