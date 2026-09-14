import { Link, useParams } from 'react-router-dom'

import { AuthenticationStatus } from '../../features/authentication/ui/AuthenticationStatus.tsx'
import { useBuyerSaleDetail } from '../../features/buyer-sale/hook/useBuyerSaleDetail.ts'
import { parseBuyerSaleId } from '../../features/buyer-sale/model/buyerSale.ts'
import { BuyerSaleDetail } from '../../features/buyer-sale/ui/BuyerSaleDetail.tsx'
import { ApiError } from '../../shared/api/apiClient.ts'
import { imageBaseUrl } from '../../shared/config/env.ts'

function PageHeader() {
  return <header className="h-16 border-b border-[var(--color-concrete-gray)] md:h-[72px]">
    <div className="mx-auto flex size-full max-w-[1440px] items-center justify-between px-5 md:px-8">
      <Link className="text-xl font-semibold tracking-[-0.04em] focus-visible:outline-2 focus-visible:outline-offset-3" to="/" aria-label="JAPDA 홈">JAPDA</Link>
      <AuthenticationStatus />
    </div>
  </header>
}

function ProductListLink() {
  return <Link className="mt-7 inline-flex min-h-[42px] items-center rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-6 font-medium text-white focus-visible:outline-2 focus-visible:outline-offset-3" to="/">상품 목록</Link>
}

function MessageState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return <div className="flex min-h-[520px] items-center justify-center border-y border-[var(--color-concrete-gray)] text-center" role="alert">
    <div>
      <p className="text-xl font-semibold">{message}</p>
      {onRetry ? <button className="mt-7 min-h-[42px] rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-6 font-medium text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3" type="button" onClick={onRetry}>다시 시도</button> : <ProductListLink />}
    </div>
  </div>
}

function LoadingState() {
  return <div role="status" aria-live="polite">
    <p className="sr-only">판매 상품 상세를 불러오는 중입니다.</p>
    <div className="grid grid-cols-[minmax(0,1.45fr)_minmax(360px,0.8fr)] border-y border-[var(--color-obsidian)] max-[720px]:block" aria-hidden="true">
      <div className="aspect-[4/3] animate-pulse bg-[var(--color-soft-mist)] motion-reduce:animate-none max-[720px]:aspect-square" />
      <div className="min-h-[480px] border-l border-[var(--color-concrete-gray)] p-[clamp(32px,4.5vw,72px)] max-[720px]:border-t max-[720px]:border-l-0 max-[720px]:px-0">
        <div className="h-7 w-24 animate-pulse rounded-full bg-[var(--color-soft-mist)] motion-reduce:animate-none" />
        <div className="mt-8 h-14 w-4/5 animate-pulse bg-[var(--color-soft-mist)] motion-reduce:animate-none" />
        <div className="mt-6 h-7 w-32 animate-pulse bg-[var(--color-soft-mist)] motion-reduce:animate-none" />
        <div className="mt-12 h-20 animate-pulse border-t border-[var(--color-concrete-gray)] bg-[var(--color-soft-mist)] motion-reduce:animate-none" />
      </div>
    </div>
    <div className="mx-auto mt-[120px] h-40 w-full max-w-[1120px] animate-pulse border-t border-[var(--color-concrete-gray)] bg-[var(--color-soft-mist)] motion-reduce:animate-none max-[720px]:mt-20" aria-hidden="true" />
  </div>
}

export function BuyerSaleDetailPage() {
  const { saleId: saleIdParam } = useParams()
  const saleId = parseBuyerSaleId(saleIdParam)
  const detail = useBuyerSaleDetail(saleId)
  const notFound = detail.error instanceof ApiError
    && detail.error.status === 404
    && detail.error.code === 'SALE_NOT_FOUND'

  let content: React.ReactNode
  if (saleId === null) {
    content = <MessageState message="유효하지 않은 상품 경로입니다." />
  } else if (detail.isPending || detail.isFetching) {
    content = <LoadingState />
  } else if (notFound) {
    content = <MessageState message="판매 상품을 찾을 수 없습니다." />
  } else if (detail.isError) {
    content = <MessageState message="판매 상품 상세를 불러오지 못했습니다." onRetry={() => void detail.retry()} />
  } else {
    content = <BuyerSaleDetail detail={detail.data} imageBaseUrl={imageBaseUrl} />
  }

  return <>
    <PageHeader />
    <main className="mx-auto w-full max-w-[1440px] px-5 pt-[22px] pb-20 md:px-8 md:pt-8 md:pb-[120px]">{content}</main>
  </>
}
