import type { BuyerOrder } from '../model/buyerOrderHistory.ts'
import { formatOrderDateTime, formatWon } from '../util/orderDisplay.ts'

interface BuyerOrderRowProps {
  order: BuyerOrder
}

export function BuyerOrderRow({ order }: BuyerOrderRowProps) {
  const isPaid = order.status === 'PAID'

  return <li className="grid grid-cols-[minmax(0,1fr)_auto] border-b border-[var(--color-concrete-gray)] py-7 min-[601px]:grid-cols-[120px_minmax(0,1fr)] min-[601px]:gap-x-7 min-[821px]:grid-cols-[170px_minmax(0,1fr)_210px] min-[821px]:gap-x-10 min-[821px]:py-8">
    <p className="col-start-1 row-start-1 m-0 text-[13px] font-semibold">
      <time dateTime={order.createdAt}>{formatOrderDateTime(order.createdAt)}</time>
    </p>

    <span className={`col-start-2 row-start-1 inline-flex min-h-[27px] shrink-0 items-center self-start justify-self-end rounded-full border px-[11px] text-[11px] font-semibold whitespace-nowrap ${isPaid ? 'border-[var(--color-obsidian)] bg-[var(--color-obsidian)] text-white' : 'border-[var(--color-concrete-gray)] bg-white text-[var(--color-steel)]'} min-[601px]:z-10 min-[601px]:mr-0`}>
      {isPaid ? '결제 완료' : '결제 대기'}
    </span>

    <div className="col-span-2 row-start-2 mt-[18px] min-w-0 min-[601px]:col-start-2 min-[601px]:row-start-1 min-[601px]:row-span-2 min-[601px]:mt-0 min-[601px]:pr-24 min-[821px]:col-start-2 min-[821px]:pr-24">
      <h2 className="m-0 text-[17px] leading-[1.45] font-semibold tracking-[-0.025em] break-keep min-[601px]:text-lg">{order.productName}</h2>
      <p className="mt-2 mb-0 text-[13px] text-[var(--color-steel)]">수량 {order.quantity.toLocaleString('ko-KR')}개 × {formatWon(order.unitPrice)}</p>
      {order.status === 'PENDING_PAYMENT' ? <p className="mt-4 mb-0 text-xs text-[var(--color-steel)]">결제 기한 <strong className="font-semibold text-[var(--color-obsidian)]">{formatOrderDateTime(order.expiresAt)}</strong></p> : null}
    </div>

    <div className="col-span-2 row-start-3 mt-[22px] border-t border-[var(--color-soft-mist)] pt-[18px] min-[601px]:col-start-2 min-[601px]:mt-5 min-[601px]:border-0 min-[601px]:pt-0 min-[821px]:col-start-3 min-[821px]:row-start-1 min-[821px]:row-span-2 min-[821px]:mt-0 min-[821px]:self-center min-[821px]:text-right">
      <span className="mr-2.5 text-[11px] text-[var(--color-steel)] min-[821px]:mb-0.5 min-[821px]:block min-[821px]:mr-0">주문 금액</span>
      <strong className="text-lg font-semibold tracking-[-0.03em] whitespace-nowrap min-[821px]:text-[22px]">{formatWon(order.totalPrice)}</strong>
    </div>

    <p className="col-span-2 row-start-4 mt-4 mb-0 text-[11px] tracking-[0.02em] text-[var(--color-steel)] min-[601px]:col-span-1 min-[601px]:col-start-1 min-[601px]:row-start-2 min-[601px]:mt-1 min-[821px]:col-start-1">주문 번호 #{order.orderId}</p>
  </li>
}
