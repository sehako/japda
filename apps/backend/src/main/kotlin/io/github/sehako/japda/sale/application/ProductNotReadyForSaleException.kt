package io.github.sehako.japda.sale.application

class ProductNotReadyForSaleException : RuntimeException("판매 준비가 완료되지 않은 상품입니다.")
