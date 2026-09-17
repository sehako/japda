import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

import type { BuyerOrder } from '../model/buyerOrderHistory.ts'
import { BuyerOrderRow } from './BuyerOrderRow.tsx'

export type BuyerOrderNextPageError = 'none' | 'general' | 'invalid-cursor'

interface BuyerOrderListProps {
  orders: BuyerOrder[] | undefined
  isLoading: boolean
  isInitialError: boolean
  hasNextPage: boolean
  isFetchingNextPage: boolean
  nextPageError: BuyerOrderNextPageError
  onRetryInitial: () => void
  onLoadMore: () => void
  onRetryNextPage: () => void
  onRestart: () => void
}

function StateView({ children, role }: { children: ReactNode; role?: 'status' | 'alert' }) {
  return <section className="grid min-h-[440px] place-items-center border-y border-t-[var(--color-obsidian)] border-b-[var(--color-concrete-gray)] text-center" role={role}>
    <div className="w-[min(calc(100%_-_32px),470px)] py-20">{children}</div>
  </section>
}

const primaryActionClassName = 'inline-flex min-h-[46px] min-w-[170px] items-center justify-center rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-6 font-medium text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-4 disabled:cursor-wait disabled:opacity-50'

export function BuyerOrderList({
  orders,
  isLoading,
  isInitialError,
  hasNextPage,
  isFetchingNextPage,
  nextPageError,
  onRetryInitial,
  onLoadMore,
  onRetryNextPage,
  onRestart,
}: BuyerOrderListProps) {
  if (isLoading) {
    return <StateView role="status">
      <p className="mb-3 text-[11px] font-semibold tracking-[0.16em] text-[var(--color-steel)]">LOADING</p>
      <h2 className="m-0 text-2xl leading-[1.35] font-semibold tracking-[-0.035em] break-keep">주문 내역을 불러오는 중입니다.</h2>
      <div className="mx-auto mt-7 grid w-[168px] gap-[9px]" aria-hidden="true">
        <span className="h-[3px] animate-pulse bg-[var(--color-obsidian)] motion-reduce:animate-none motion-reduce:opacity-50" />
        <span className="h-[3px] w-[72%] animate-pulse bg-[var(--color-obsidian)] delay-150 motion-reduce:animate-none motion-reduce:opacity-50" />
        <span className="h-[3px] w-[42%] animate-pulse bg-[var(--color-obsidian)] delay-300 motion-reduce:animate-none motion-reduce:opacity-50" />
      </div>
    </StateView>
  }

  if (isInitialError) {
    return <StateView role="alert">
      <p className="mb-3 text-[11px] font-semibold tracking-[0.16em] text-[var(--color-steel)]">ERROR</p>
      <h2 className="m-0 text-2xl leading-[1.35] font-semibold tracking-[-0.035em] break-keep">주문 내역을 불러오지 못했습니다.</h2>
      <p className="mt-3.5 mb-0 text-sm text-[var(--color-steel)] break-keep">잠시 후 다시 시도해 주세요.</p>
      <button className={`${primaryActionClassName} mt-7`} type="button" onClick={onRetryInitial}>다시 시도</button>
    </StateView>
  }

  if (!orders || orders.length === 0) {
    return <StateView>
      <p className="mb-3 text-[11px] font-semibold tracking-[0.16em] text-[var(--color-steel)]">NO ORDERS</p>
      <h2 className="m-0 text-2xl leading-[1.35] font-semibold tracking-[-0.035em] break-keep">아직 구매 내역이 없습니다.</h2>
      <p className="mt-3.5 mb-0 text-sm text-[var(--color-steel)] break-keep">새로운 상품을 둘러보세요.</p>
      <Link className={`${primaryActionClassName} mt-7`} to="/">상품 보러 가기</Link>
    </StateView>
  }

  return <section className="border-t border-[var(--color-obsidian)]" aria-labelledby="order-list-heading">
    <div className="flex min-h-[50px] items-center justify-between gap-6 border-b border-[var(--color-concrete-gray)] text-[11px] font-semibold tracking-[0.08em] text-[var(--color-steel)] min-[601px]:min-h-[58px]">
      <strong className="text-[var(--color-obsidian)]" id="order-list-heading">주문 내역</strong>
      <span>최신 주문순</span>
    </div>
    <ol className="m-0 list-none p-0">
      {orders.map((order) => <BuyerOrderRow key={order.orderId} order={order} />)}
    </ol>

    {nextPageError === 'general' ? <div className="mt-4 text-center text-xs text-[var(--color-steel)]" role="alert">
      <p className="m-0">추가 주문을 불러오지 못했습니다.</p>
      <button className={`${primaryActionClassName} mt-4`} type="button" onClick={onRetryNextPage}>다시 시도</button>
    </div> : null}

    {nextPageError === 'invalid-cursor' ? <div className="mt-4 text-center text-xs text-[var(--color-steel)]" role="alert">
      <p className="m-0">주문 내역을 처음부터 다시 불러와야 합니다.</p>
      <button className={`${primaryActionClassName} mt-4`} type="button" onClick={onRestart}>처음부터 다시 불러오기</button>
    </div> : null}

    {hasNextPage && nextPageError === 'none' ? <div className="flex justify-center pt-9 min-[601px]:pt-11">
      <button className={primaryActionClassName} type="button" disabled={isFetchingNextPage} onClick={onLoadMore}>
        {isFetchingNextPage ? '주문을 불러오는 중입니다.' : '주문 더 불러오기'}
      </button>
    </div> : null}
  </section>
}
