package io.github.sehako.japda.auth.domain.repository

interface PrincipalIdentityRepository {
    fun findBuyerId(userId: Long): Long?
    fun findSellerId(userId: Long): Long?
    fun createBuyerLink(userId: Long): Long
}
