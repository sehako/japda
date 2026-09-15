import { Link, useLocation } from 'react-router-dom'

import { useSaleScheduling } from '../../features/sale-scheduling/hook/useSaleScheduling.ts'
import { SaleSchedulingForm } from '../../features/sale-scheduling/ui/SaleSchedulingForm.tsx'
import type { ReadyProduct } from '../../features/sale-scheduling/model/saleScheduling.ts'
import { useCurrentUser } from '../../features/authentication/hook/useCurrentUser.ts'
import { startGoogleLogin } from '../../features/authentication/util/loginFlow.ts'

function readPreselectedProduct(state: unknown): ReadyProduct | undefined {
  if (typeof state !== 'object' || state === null) return undefined
  const product = (state as Record<string, unknown>).preselectedProduct
  if (typeof product !== 'object' || product === null) return undefined
  const { id, name } = product as Record<string, unknown>
  if (typeof id !== 'number' || !Number.isSafeInteger(id) || id <= 0) return undefined
  if (typeof name !== 'string' || name.trim().length === 0) return undefined
  return { id, name }
}

function SellerSaleSchedulingContent() {
  const location = useLocation()
  const scheduling = useSaleScheduling(readPreselectedProduct(location.state))
  if (scheduling.authError) return <SellerAccessState status={scheduling.authError} />
  return <><header className="h-[72px] border-b border-[var(--color-concrete-gray)] max-md:h-auto"><div className="mx-auto flex size-full max-w-[1440px] items-center justify-between gap-8 px-8 max-md:min-h-16 max-md:flex-col max-md:items-start max-md:gap-3 max-md:px-5 max-md:py-4"><Link className="flex items-baseline gap-3" to="/seller/products/new" aria-label="JAPDA 판매자 센터"><span className="text-xl font-semibold tracking-[-0.04em]">JAPDA</span><span className="text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)]">SELLER</span></Link><nav className="flex gap-6 text-[13px] max-md:w-full" aria-label="판매자 메뉴"><Link className="text-[var(--color-steel)] hover:text-[var(--color-obsidian)]" to="/seller/products/new">상품 등록</Link><Link className="font-semibold" to="/seller/sales/new" aria-current="page">판매 일정 등록</Link></nav></div></header><main className="mx-auto w-full max-w-[1120px] px-8 pt-16 pb-20 max-md:px-5 max-md:pt-10 max-md:pb-14"><header className="mb-9 max-md:mb-7"><h1 className="text-[clamp(32px,4vw,48px)] leading-[1.1] font-semibold tracking-[-0.04em]">판매 일정 등록</h1></header><SaleSchedulingForm scheduling={scheduling} /></main></>
}

function SellerAccessState({ status, retry }: { status: 'checking' | 'unauthenticated' | 'seller-link-required' | 'error'; retry?: () => void }) {
  const message = status === 'checking' ? '로그인 상태를 확인하는 중입니다.'
    : status === 'unauthenticated' ? '로그인이 필요합니다. 다시 로그인해 주세요.'
      : status === 'seller-link-required' ? '판매자 계정 연결이 필요합니다.' : '로그인 상태를 확인하지 못했습니다.'
  return <main className="mx-auto max-w-[1120px] px-5 py-20 text-center" role="alert">
    <p>{message}</p>
    {status === 'unauthenticated' ? <button className="mt-6 rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" type="button" onClick={() => startGoogleLogin()}>Google 로그인</button> : null}
    {status === 'error' ? <button className="mt-6 rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" type="button" onClick={retry}>다시 확인</button> : null}
  </main>
}

export function SellerSaleSchedulingPage() {
  const { status, retry } = useCurrentUser()
  return status === 'authenticated' ? <SellerSaleSchedulingContent /> : <SellerAccessState status={status} retry={retry} />
}
