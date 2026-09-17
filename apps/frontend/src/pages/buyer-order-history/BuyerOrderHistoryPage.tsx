import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { useCurrentUser } from '../../features/authentication/hook/useCurrentUser.ts'
import { startGoogleLogin } from '../../features/authentication/util/loginFlow.ts'
import { useBuyerOrderHistory } from '../../features/buyer-order-history/hook/useBuyerOrderHistory.ts'
import {
  BuyerOrderList,
  type BuyerOrderNextPageError,
} from '../../features/buyer-order-history/ui/BuyerOrderList.tsx'
import { ApiError } from '../../shared/api/apiClient.ts'

const primaryActionClassName = 'inline-flex min-h-[46px] min-w-[170px] items-center justify-center rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-6 font-medium text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-4'

function PageHeader({ email }: { email?: string }) {
  return <header className="h-16 border-b border-[var(--color-concrete-gray)] md:h-[72px]">
    <div className="mx-auto grid size-full max-w-[1440px] grid-cols-[1fr_auto_1fr] items-center px-5 md:px-8">
      <Link className="w-fit text-xl font-semibold tracking-[-0.04em]" to="/" aria-label="JAPDA 홈">JAPDA</Link>
      <nav className="flex items-center gap-4 min-[381px]:gap-5 md:gap-8" aria-label="주요 메뉴">
        <Link className="inline-flex min-h-11 items-center text-xs font-semibold tracking-[0.08em] text-[var(--color-steel)] hover:text-[var(--color-obsidian)]" to="/">SHOP</Link>
        <Link className="relative inline-flex min-h-11 items-center text-xs font-semibold tracking-[0.08em] after:absolute after:right-0 after:bottom-[-10px] after:left-0 after:h-px after:bg-[var(--color-obsidian)] md:after:bottom-[-14px]" to="/orders" aria-current="page">ORDERS</Link>
      </nav>
      <span className="min-w-0 justify-self-end truncate text-xs font-medium max-[600px]:max-w-0 max-[600px]:overflow-hidden md:max-w-[220px]">{email}</span>
    </div>
  </header>
}

function AccessState({
  title,
  message,
  children,
  role = 'alert',
}: {
  title: string
  message: string
  children?: ReactNode
  role?: 'alert' | 'status'
}) {
  return <section className="grid min-h-[440px] place-items-center border-y border-t-[var(--color-obsidian)] border-b-[var(--color-concrete-gray)] text-center" role={role}>
    <div className="w-[min(calc(100%_-_32px),470px)] py-20">
      <h2 className="m-0 text-2xl leading-[1.35] font-semibold tracking-[-0.035em] break-keep">{title}</h2>
      <p className="mt-3.5 mb-0 text-sm text-[var(--color-steel)] break-keep">{message}</p>
      {children ? <div className="mt-7">{children}</div> : null}
    </div>
  </section>
}

export function BuyerOrderHistoryPage() {
  const currentUser = useCurrentUser()
  const userId = currentUser.status === 'authenticated' ? currentUser.user?.id : undefined
  const orderHistory = useBuyerOrderHistory(userId, currentUser.status === 'unauthenticated')
  const orderError = orderHistory.error
  const needsLogin = orderError instanceof ApiError
    && orderError.status === 401
    && orderError.code === 'AUTH_UNAUTHENTICATED'
  const needsBuyerLink = orderError instanceof ApiError
    && orderError.status === 403
    && orderError.code === 'AUTH_BUYER_LINK_REQUIRED'
  const invalidCursor = orderHistory.isLoadMoreError
    && orderError instanceof ApiError
    && orderError.code === 'ORDER_CURSOR_INVALID'
  const nextPageError: BuyerOrderNextPageError = orderHistory.isLoadMoreError
    ? invalidCursor ? 'invalid-cursor' : 'general'
    : 'none'

  let content: ReactNode
  if (currentUser.status === 'checking') {
    content = <AccessState title="로그인 상태를 확인하고 있습니다." message="잠시만 기다려 주세요." role="status" />
  } else if (currentUser.status === 'error') {
    content = <AccessState title="로그인 상태를 확인하지 못했습니다." message="잠시 후 다시 시도해 주세요.">
      <button className={primaryActionClassName} type="button" onClick={currentUser.retry}>다시 시도</button>
    </AccessState>
  } else if (currentUser.status === 'unauthenticated' || needsLogin) {
    content = <AccessState title="로그인이 필요합니다." message="주문 내역은 로그인 후 확인할 수 있습니다.">
      <button className={primaryActionClassName} type="button" onClick={() => startGoogleLogin()}>로그인</button>
    </AccessState>
  } else if (needsBuyerLink) {
    content = <AccessState title="구매자 정보 연결이 필요합니다." message="현재 계정으로 주문 내역을 확인할 수 없습니다.">
      <Link className={primaryActionClassName} to="/">홈으로 돌아가기</Link>
    </AccessState>
  } else {
    content = <BuyerOrderList
      orders={orderHistory.orders}
      isLoading={orderHistory.isPending}
      isInitialError={orderHistory.isError && !orderHistory.isLoadMoreError}
      hasNextPage={orderHistory.hasNextPage === true}
      isFetchingNextPage={orderHistory.isFetchingNextPage}
      nextPageError={nextPageError}
      onRetryInitial={() => { void orderHistory.retryInitial() }}
      onLoadMore={() => { void orderHistory.loadMore() }}
      onRetryNextPage={() => { void orderHistory.retryLoadMore() }}
      onRestart={() => { void orderHistory.restart() }}
    />
  }

  return <>
    <PageHeader email={currentUser.status === 'authenticated' ? currentUser.user?.email : undefined} />
    <main className="mx-auto w-[min(calc(100%_-_40px),1200px)] pt-12 pb-20 min-[601px]:w-[min(calc(100%_-_64px),1200px)] min-[601px]:pt-16 min-[601px]:pb-32 min-[821px]:pt-[88px]">
      <header className="grid items-end gap-6 pb-8 min-[821px]:grid-cols-[minmax(0,1fr)_auto] min-[821px]:gap-8 min-[821px]:pb-9">
        <div>
          <p className="mb-3 text-[11px] font-semibold tracking-[0.18em]">PURCHASE HISTORY</p>
          <h1 className="m-0 text-[clamp(46px,7vw,88px)] leading-[0.92] font-semibold tracking-[-0.065em]">Orders</h1>
        </div>
        <p className="m-0 max-w-[280px] text-sm text-[var(--color-steel)] break-keep min-[601px]:text-[15px] min-[821px]:mb-1">구매한 상품과 주문 상태를 확인할 수 있습니다.</p>
      </header>
      {content}
    </main>
    <footer className="border-t border-[var(--color-concrete-gray)]">
      <div className="mx-auto flex min-h-[100px] w-[min(calc(100%_-_40px),1440px)] items-center justify-between text-[11px] text-[var(--color-steel)] min-[601px]:min-h-[116px] min-[601px]:w-[min(calc(100%_-_64px),1440px)]">
        <span className="text-sm font-semibold tracking-[-0.03em] text-[var(--color-obsidian)]">JAPDA</span>
        <span className="max-[600px]:hidden">© 2026 JAPDA. ALL RIGHTS RESERVED.</span>
      </div>
    </footer>
  </>
}
