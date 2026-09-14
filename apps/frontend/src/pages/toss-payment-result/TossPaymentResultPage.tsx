import { Link, useSearchParams } from 'react-router-dom'

import { useBuyerPaymentConfirmation } from '../../features/buyer-checkout/hook/useBuyerPaymentConfirmation.ts'
import type { BuyerPaymentConfirmationState } from '../../features/buyer-checkout/hook/useBuyerPaymentConfirmation.ts'
import { parseTossSuccessParameters } from '../../features/buyer-checkout/model/tossPaymentResult.ts'
import { parseCheckoutQuantity } from '../../features/buyer-checkout/model/buyerCheckout.ts'
import { parseBuyerSaleId } from '../../features/buyer-sale/model/buyerSale.ts'
import { buyerIdConfig } from '../../shared/config/env.ts'

type ResultView = {
  title: string
  message: string
  detail?: string
  progress?: boolean
  receipt?: BuyerPaymentConfirmationState & { kind: 'paid' }
  link?: 'checkout' | 'catalog'
  quiet?: boolean
  urgent?: boolean
  role: 'status' | 'alert'
}

function getCheckoutReturnPath(params: URLSearchParams): string | null {
  if (params.getAll('saleId').length !== 1 || params.getAll('quantity').length !== 1) return null
  const saleId = parseBuyerSaleId(params.get('saleId') ?? undefined)
  const quantity = parseCheckoutQuantity(params.get('quantity'))
  return saleId !== null && quantity !== null ? `/checkout/${saleId}?quantity=${quantity}` : null
}

function formatPrice(amount: number) {
  return `${new Intl.NumberFormat('ko-KR').format(amount)}원`
}

function formatApprovedAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23', timeZone: 'Asia/Seoul',
  }).format(new Date(value))
}

function viewForConfirmation(state: BuyerPaymentConfirmationState): ResultView {
  switch (state.kind) {
    case 'confirming':
      return { title: '결제 결과를 확인하고 있습니다.', message: '결제 승인 결과를 확인하는 중입니다.', progress: true, role: 'status' }
    case 'processing':
      return { title: '결제 승인 처리 중입니다.', message: '결제 결과를 자동으로 확인하고 있습니다.', detail: '결제 상태가 확정될 때까지 잠시만 기다려 주세요.', progress: true, role: 'status' }
    case 'paid':
      return { title: '결제가 완료됐습니다.', message: '주문이 정상적으로 확정됐습니다.', receipt: state, link: 'catalog', role: 'status' }
    case 'failed':
      return { title: '결제에 실패했습니다.', message: '결제가 완료되지 않았습니다.', detail: '결제 화면으로 돌아가 다시 결제할 수 있습니다.', link: 'checkout', urgent: true, role: 'alert' }
    case 'expired':
      return { title: '주문이 만료됐습니다.', message: '이 주문으로는 결제를 진행할 수 없습니다.', detail: '결제 화면으로 돌아가 새 주문으로 다시 시도해 주세요.', link: 'checkout', urgent: true, role: 'alert' }
    case 'review':
      return { title: '결제 결과를 아직 확인할 수 없습니다.', message: '결제가 완료됐을 수 있으니 잠시만 기다려 주세요.', detail: state.reason === 'timeout' ? '결제 결과 확인 시간이 초과됐습니다.' : state.reason === 'manual' ? '결제 상태에 별도 확인이 필요합니다.' : '승인 요청 중 오류가 발생했습니다.', link: 'catalog', quiet: true, role: 'alert' }
    case 'invalid':
      return { title: '결제 정보를 확인할 수 없습니다.', message: state.reason === 'invalid' ? '요청 정보가 올바르지 않거나 필요한 설정이 없어 승인을 진행할 수 없습니다.' : '현재 결제 정보로는 승인을 진행할 수 없습니다.', link: 'catalog', role: 'alert' }
  }
}

export function TossPaymentResultPage({ result }: { result: 'success' | 'fail' }) {
  const [searchParams] = useSearchParams()
  const checkoutPath = getCheckoutReturnPath(searchParams)
  const successParameters = result === 'success' ? parseTossSuccessParameters(searchParams) : null
  const confirmation = useBuyerPaymentConfirmation(successParameters, buyerIdConfig.valid ? buyerIdConfig.value : null)
  const view = result === 'success'
    ? viewForConfirmation(confirmation)
    : searchParams.get('code') === 'PAY_PROCESS_CANCELED'
      ? { title: '결제를 취소했습니다.', message: '결제가 완료되지 않았습니다.', detail: '결제 화면으로 돌아가 다시 결제할 수 있습니다.', link: 'checkout', role: 'alert', urgent: true } satisfies ResultView
      : { title: '결제에 실패했습니다.', message: '결제 인증에 실패했습니다.', detail: '결제 화면으로 돌아가 다시 결제할 수 있습니다.', link: 'checkout', role: 'alert', urgent: true } satisfies ResultView

  const linkToCheckout = view.link === 'checkout' && checkoutPath !== null

  return <>
    <header className="h-[72px] border-b border-[var(--color-concrete-gray)] max-[720px]:h-16"><div className="mx-auto flex size-full max-w-[1440px] items-center px-8 max-[720px]:px-5 max-[420px]:px-[14px]"><Link className="text-xl font-semibold tracking-[-0.04em]" to="/" aria-label="JAPDA 홈">JAPDA</Link></div></header>
    <main className="mx-auto w-full max-w-[1200px] px-8 pt-20 pb-[120px] max-[720px]:px-5 max-[720px]:pt-12 max-[720px]:pb-20 max-[420px]:px-[14px]">
      <section className="flex min-h-[440px] items-center justify-center border-y border-[var(--color-concrete-gray)] text-center max-[720px]:min-h-[400px]" aria-labelledby="payment-result-title" role={view.role} aria-live={view.role === 'status' ? 'polite' : undefined}>
        <div className="w-[calc(100%-40px)] max-w-[480px] py-16 max-[720px]:w-[calc(100%-24px)] max-[720px]:py-14 max-[420px]:w-[calc(100%-16px)]">
          <p className={`mb-[14px] text-[11px] font-semibold tracking-[0.16em] ${view.urgent ? 'text-[var(--color-signal)]' : 'text-[var(--color-steel)]'}`}>결제 결과</p>
          <h1 className="break-keep text-2xl leading-[1.35] font-semibold tracking-[-0.035em] max-[420px]:text-[22px]" id="payment-result-title">{view.title}</h1>
          <p className="mt-4 break-keep text-base leading-[1.6] max-[420px]:text-[15px]">{view.message}</p>
          {view.detail ? <p className="mt-2.5 break-keep text-sm leading-[1.6] text-[var(--color-steel)]">{view.detail}</p> : null}
          {view.progress ? <div className="mt-7 flex justify-center gap-[7px]" aria-hidden="true"><span className="size-[6px] rounded-full bg-[var(--color-obsidian)]" /><span className="size-[6px] rounded-full bg-[var(--color-obsidian)] opacity-[0.55]" /><span className="size-[6px] rounded-full bg-[var(--color-obsidian)] opacity-25" /></div> : null}
          {view.receipt ? <dl className="mt-8 border-t border-[var(--color-obsidian)] text-left">
            <div className="flex justify-between gap-6 border-b border-[var(--color-concrete-gray)] py-[15px] max-[420px]:gap-3"><dt className="shrink-0 text-[var(--color-steel)]">주문 번호</dt><dd className="m-0 min-w-0 text-right font-medium break-all">{view.receipt.payment.orderId}</dd></div>
            <div className="flex justify-between gap-6 border-b border-[var(--color-concrete-gray)] py-[15px] max-[420px]:gap-3"><dt className="shrink-0 text-[var(--color-steel)]">확정 금액</dt><dd className="m-0 min-w-0 text-right text-xl font-semibold tracking-[-0.025em] break-all">{formatPrice(view.receipt.payment.totalAmount)}</dd></div>
            <div className="flex justify-between gap-6 border-b border-[var(--color-concrete-gray)] py-[15px] max-[420px]:gap-3"><dt className="shrink-0 text-[var(--color-steel)]">승인 시각</dt><dd className="m-0 min-w-0 text-right font-medium break-all">{formatApprovedAt(view.receipt.payment.approvedAt)}</dd></div>
          </dl> : null}
          {view.link ? <div className="mt-8"><Link className={view.quiet ? 'inline-flex min-h-[42px] items-center text-[var(--color-steel)] underline underline-offset-[3px] hover:text-[var(--color-obsidian)]' : 'inline-flex min-h-[42px] items-center justify-center rounded-full bg-[var(--color-obsidian)] px-6 font-medium text-white hover:opacity-80'} to={linkToCheckout ? checkoutPath : '/'}>{linkToCheckout ? '결제 화면으로 돌아가기' : '상품 목록으로 돌아가기'}</Link></div> : null}
        </div>
      </section>
    </main>
  </>
}
