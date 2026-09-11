import type { BuyerSaleProduct } from '../model/buyerSale.ts'
import { BuyerSaleCard } from './BuyerSaleCard.tsx'

interface BuyerSaleListProps {
  sales: BuyerSaleProduct[] | undefined
  isLoading: boolean
  isError: boolean
  onRetry: () => void
  imageBaseUrl: string
}

function ListState({ children, role }: { children: React.ReactNode; role?: 'status' | 'alert' }) {
  return <div className="flex min-h-[360px] items-center justify-center border-y border-[var(--color-concrete-gray)] text-center" role={role}>{children}</div>
}

export function BuyerSaleList({ sales, isLoading, isError, onRetry, imageBaseUrl }: BuyerSaleListProps) {
  if (isLoading) return <ListState role="status"><div><span className="mx-auto mb-4 block size-6 animate-spin rounded-full border border-[var(--color-concrete-gray)] border-t-[var(--color-obsidian)] motion-reduce:animate-none" aria-hidden="true" /><p className="text-lg font-semibold">판매 상품을 불러오는 중입니다.</p></div></ListState>
  if (isError) return <ListState role="alert"><div><p className="text-lg font-semibold">판매 상품을 불러오지 못했습니다.</p><button className="mt-5 min-h-[42px] rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-5 text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3" type="button" onClick={onRetry}>다시 시도</button></div></ListState>
  if (!sales || sales.length === 0) return <ListState><p className="text-lg font-semibold">선택한 날짜에 판매 상품이 없습니다.</p></ListState>
  return <div className="grid grid-cols-1 gap-x-4 gap-y-9 min-[421px]:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
    {sales.map((sale) => <BuyerSaleCard key={sale.saleId} sale={sale} imageBaseUrl={imageBaseUrl} />)}
  </div>
}
