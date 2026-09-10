package io.github.sehako.japda.product.infrastructure

import io.github.sehako.japda.product.domain.Product
import io.github.sehako.japda.product.domain.ProductRepository
import io.github.sehako.japda.product.domain.ReadyProductCursorBoundary
import io.github.sehako.japda.product.domain.ReadyProductQuery
import io.github.sehako.japda.product.domain.ReadyProductSort
import io.github.sehako.japda.product.domain.ReadyProductSummary
import org.springframework.stereotype.Repository

@Repository
class ProductRepositoryImpl(
	private val productJpaRepository: ProductJpaRepository,
) : ProductRepository {
	override fun save(product: Product): Product = productJpaRepository.save(product)

	override fun findById(id: Long): Product? = productJpaRepository.findById(id).orElse(null)

	override fun findByIdForUpdate(id: Long): Product? = productJpaRepository.findByIdForUpdate(id)

	override fun findReadyProducts(query: ReadyProductQuery): List<ReadyProductSummary> {
		val rows = when (query.sort) {
			ReadyProductSort.LATEST -> when (val cursor = query.cursor) {
				null -> productJpaRepository.findReadyLatest(query.sellerId, query.limit)
				is ReadyProductCursorBoundary.Id -> productJpaRepository.findReadyLatestAfter(query.sellerId, cursor.id, query.limit)
				is ReadyProductCursorBoundary.Name -> error("LATEST 정렬에는 상품명 커서를 사용할 수 없습니다")
			}
			ReadyProductSort.OLDEST -> when (val cursor = query.cursor) {
				null -> productJpaRepository.findReadyOldest(query.sellerId, query.limit)
				is ReadyProductCursorBoundary.Id -> productJpaRepository.findReadyOldestAfter(query.sellerId, cursor.id, query.limit)
				is ReadyProductCursorBoundary.Name -> error("OLDEST 정렬에는 상품명 커서를 사용할 수 없습니다")
			}
			ReadyProductSort.NAME_ASC -> when (val cursor = query.cursor) {
				null -> productJpaRepository.findReadyNameAscending(query.sellerId, query.limit)
				is ReadyProductCursorBoundary.Name -> productJpaRepository.findReadyNameAscendingAfter(query.sellerId, cursor.name, cursor.id, query.limit)
				is ReadyProductCursorBoundary.Id -> error("NAME_ASC 정렬에는 식별자 커서를 사용할 수 없습니다")
			}
			ReadyProductSort.NAME_DESC -> when (val cursor = query.cursor) {
				null -> productJpaRepository.findReadyNameDescending(query.sellerId, query.limit)
				is ReadyProductCursorBoundary.Name -> productJpaRepository.findReadyNameDescendingAfter(query.sellerId, cursor.name, cursor.id, query.limit)
				is ReadyProductCursorBoundary.Id -> error("NAME_DESC 정렬에는 식별자 커서를 사용할 수 없습니다")
			}
		}
		return rows.map { ReadyProductSummary(it.id, it.name) }
	}
}
