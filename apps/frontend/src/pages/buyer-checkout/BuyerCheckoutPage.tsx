import { Link, useParams, useSearchParams } from 'react-router-dom'

import { parseCheckoutQuantity } from '../../features/buyer-checkout/model/buyerCheckout.ts'
import { BuyerCheckoutContent } from '../../features/buyer-checkout/ui/BuyerCheckoutContent.tsx'
import { parseBuyerSaleId } from '../../features/buyer-sale/model/buyerSale.ts'
import { buyerIdConfig, BUYER_ID_CONFIG_ERROR } from '../../shared/config/env.ts'

function MessageState({ message }: { message: string }) {
  return <div className="flex min-h-[480px] items-center justify-center border-y border-[var(--color-concrete-gray)] text-center" role="alert">
    <div><p className="text-xl font-semibold">{message}</p><Link className="mt-7 inline-flex min-h-[42px] items-center rounded-full bg-[var(--color-obsidian)] px-6 text-white" to="/">상품 목록</Link></div>
  </div>
}

export function BuyerCheckoutPage({ buyerId }: { buyerId?: number | null }) {
  const { saleId: saleIdParam } = useParams()
  const [searchParams] = useSearchParams()
  const saleId = parseBuyerSaleId(saleIdParam)
  const quantityValues = searchParams.getAll('quantity')
  const quantity = quantityValues.length === 1 ? parseCheckoutQuantity(quantityValues[0]) : null
  const configuredBuyerId = buyerId === undefined ? (buyerIdConfig.valid ? buyerIdConfig.value : null) : buyerId

  let content: React.ReactNode
  if (saleId === null || quantity === null) {
    content = <MessageState message="유효하지 않은 체크아웃 경로입니다." />
  } else if (configuredBuyerId === null) {
    content = <MessageState message={BUYER_ID_CONFIG_ERROR} />
  } else {
    content = <BuyerCheckoutContent saleId={saleId} quantity={quantity} buyerId={configuredBuyerId} />
  }

  return <>
    <header className="h-16 border-b border-[var(--color-concrete-gray)] md:h-[72px]"><div className="mx-auto flex size-full max-w-[1440px] items-center justify-between px-5 md:px-8"><Link className="text-xl font-semibold tracking-[-0.04em]" to="/" aria-label="JAPDA 홈">JAPDA</Link><button className="min-h-10 min-w-20 rounded-full bg-[var(--color-obsidian)] px-[18px] font-medium text-white md:min-h-[42px] md:min-w-[92px]" type="button">로그인</button></div></header>
    <main className="mx-auto w-full max-w-[1200px] px-5 pt-[22px] pb-20 md:px-8 md:pt-8 md:pb-[120px]">{content}</main>
  </>
}
