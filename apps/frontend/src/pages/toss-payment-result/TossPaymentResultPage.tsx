import { Link, useSearchParams } from 'react-router-dom'

import { parseCheckoutQuantity } from '../../features/buyer-checkout/model/buyerCheckout.ts'
import { hasTossSuccessParameters } from '../../features/buyer-checkout/model/tossPaymentResult.ts'
import { parseBuyerSaleId } from '../../features/buyer-sale/model/buyerSale.ts'

function getCheckoutReturnPath(params: URLSearchParams): string | null {
  if (params.getAll('saleId').length !== 1 || params.getAll('quantity').length !== 1) return null
  const saleId = parseBuyerSaleId(params.get('saleId') ?? undefined)
  const quantity = parseCheckoutQuantity(params.get('quantity'))
  return saleId !== null && quantity !== null ? `/checkout/${saleId}?quantity=${quantity}` : null
}

export function TossPaymentResultPage({ result }: { result: 'success' | 'fail' }) {
  const [searchParams] = useSearchParams()
  const checkoutPath = getCheckoutReturnPath(searchParams)
  const message = result === 'success'
    ? hasTossSuccessParameters(searchParams)
      ? '결제 인증 경로로 돌아왔지만 결제가 완료되지 않았습니다.'
      : '결제 인증 결과를 확인할 수 없습니다.'
    : searchParams.get('code') === 'PAY_PROCESS_CANCELED'
      ? '결제를 취소했습니다.'
      : '결제 인증에 실패했습니다.'

  return <>
    <header className="h-16 border-b border-[var(--color-concrete-gray)] md:h-[72px]"><div className="mx-auto flex size-full max-w-[1440px] items-center px-5 md:px-8"><Link className="text-xl font-semibold tracking-[-0.04em]" to="/" aria-label="JAPDA 홈">JAPDA</Link></div></header>
    <main className="mx-auto w-full max-w-[1200px] px-5 py-20 md:px-8">
      <div className="flex min-h-[360px] items-center justify-center border-y border-[var(--color-concrete-gray)] text-center" role="alert">
        <div>
          <h1 className="text-2xl font-semibold">결제 인증 결과</h1>
          <p className="mt-4 text-base">{message}</p>
          <Link className="mt-7 inline-flex min-h-[42px] items-center rounded-full bg-[var(--color-obsidian)] px-6 text-white" to={checkoutPath ?? '/'}>{checkoutPath ? '체크아웃으로 돌아가기' : '상품 목록'}</Link>
        </div>
      </div>
    </main>
  </>
}
