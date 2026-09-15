import { Link, useParams, useSearchParams } from 'react-router-dom'

import { AuthenticationStatus } from '../../features/authentication/ui/AuthenticationStatus.tsx'
import { useCurrentUser } from '../../features/authentication/hook/useCurrentUser.ts'
import { startGoogleLogin } from '../../features/authentication/util/loginFlow.ts'
import { parseCheckoutQuantity } from '../../features/buyer-checkout/model/buyerCheckout.ts'
import { BuyerCheckoutContent } from '../../features/buyer-checkout/ui/BuyerCheckoutContent.tsx'
import { parseBuyerSaleId } from '../../features/buyer-sale/model/buyerSale.ts'

function MessageState({ message }: { message: string }) {
  return <div className="flex min-h-[480px] items-center justify-center border-y border-[var(--color-concrete-gray)] text-center" role="alert">
    <div><p className="text-xl font-semibold">{message}</p><Link className="mt-7 inline-flex min-h-[42px] items-center rounded-full bg-[var(--color-obsidian)] px-6 text-white" to="/">상품 목록</Link></div>
  </div>
}

export function BuyerCheckoutPage() {
  const auth = useCurrentUser()
  const { saleId: saleIdParam } = useParams()
  const [searchParams] = useSearchParams()
  const saleId = parseBuyerSaleId(saleIdParam)
  const quantityValues = searchParams.getAll('quantity')
  const quantity = quantityValues.length === 1 ? parseCheckoutQuantity(quantityValues[0]) : null

  let content: React.ReactNode
  if (saleId === null || quantity === null) {
    content = <MessageState message="유효하지 않은 체크아웃 경로입니다." />
  } else if (auth.status === 'checking') {
    content = <p className="py-20 text-center" role="status">로그인 상태를 확인하는 중입니다.</p>
  } else if (auth.status === 'unauthenticated') {
    content = <div className="py-20 text-center" role="alert"><p>로그인이 필요합니다.</p><button className="mt-5 rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" type="button" onClick={() => startGoogleLogin()}>로그인</button></div>
  } else if (auth.status === 'error') {
    content = <div className="py-20 text-center" role="alert"><p>로그인 상태를 확인하지 못했습니다.</p><button className="mt-5 rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" type="button" onClick={auth.retry}>다시 확인</button></div>
  } else {
    content = <BuyerCheckoutContent saleId={saleId} quantity={quantity} />
  }

  return <>
    <header className="h-16 border-b border-[var(--color-concrete-gray)] md:h-[72px]"><div className="mx-auto flex size-full max-w-[1440px] items-center justify-between px-5 md:px-8"><Link className="text-xl font-semibold tracking-[-0.04em]" to="/" aria-label="JAPDA 홈">JAPDA</Link><AuthenticationStatus /></div></header>
    <main className="mx-auto w-full max-w-[1200px] px-5 pt-[22px] pb-20 md:px-8 md:pt-8 md:pb-[120px]">{content}</main>
  </>
}
