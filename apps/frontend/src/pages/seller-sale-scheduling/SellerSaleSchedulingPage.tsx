import { Link } from 'react-router-dom'

import { useSaleScheduling } from '../../features/sale-scheduling/hook/useSaleScheduling.ts'
import { SaleSchedulingForm } from '../../features/sale-scheduling/ui/SaleSchedulingForm.tsx'

export function SellerSaleSchedulingPage() {
  const scheduling = useSaleScheduling()
  return <><header className="h-[72px] border-b border-[var(--color-concrete-gray)] max-md:h-auto"><div className="mx-auto flex size-full max-w-[1440px] items-center justify-between gap-8 px-8 max-md:min-h-16 max-md:flex-col max-md:items-start max-md:gap-3 max-md:px-5 max-md:py-4"><Link className="flex items-baseline gap-3" to="/seller/products/new" aria-label="JAPDA 판매자 센터"><span className="text-xl font-semibold tracking-[-0.04em]">JAPDA</span><span className="text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)]">SELLER</span></Link><nav className="flex gap-6 text-[13px] max-md:w-full" aria-label="판매자 메뉴"><Link className="text-[var(--color-steel)] hover:text-[var(--color-obsidian)]" to="/seller/products/new">상품 등록</Link><Link className="font-semibold" to="/seller/sales/new" aria-current="page">판매 일정 등록</Link></nav></div></header><main className="mx-auto w-full max-w-[1120px] px-8 pt-16 pb-20 max-md:px-5 max-md:pt-10 max-md:pb-14"><header className="mb-9 max-md:mb-7"><h1 className="text-[clamp(32px,4vw,48px)] leading-[1.1] font-semibold tracking-[-0.04em]">판매 일정 등록</h1></header><SaleSchedulingForm scheduling={scheduling} /></main></>
}
