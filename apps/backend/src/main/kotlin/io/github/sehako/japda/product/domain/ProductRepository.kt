package io.github.sehako.japda.product.domain

interface ProductRepository {

	fun save(product: Product): Product
}
