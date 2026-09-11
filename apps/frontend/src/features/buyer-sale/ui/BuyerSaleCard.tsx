import { useState } from 'react'
import { Link } from 'react-router-dom'

import { buildProductImageUrl, formatKoreanPrice, getSaleStatusLabel } from '../model/buyerSale.ts'
import type { BuyerSaleProduct } from '../model/buyerSale.ts'

interface BuyerSaleCardProps {
  sale: BuyerSaleProduct
  imageBaseUrl: string
}

function ProductImage({ name, url }: { name: string; url: string | null }) {
  const [failed, setFailed] = useState(false)
  if (url === null || failed) {
    return <div className="grid size-full place-items-center text-xs tracking-[0.08em] text-[var(--color-faint-gray)]" role="img" aria-label={`${name} 이미지 없음`}>이미지 없음</div>
  }
  return <img className="block size-full object-cover" src={url} alt={name} loading="lazy" onError={() => setFailed(true)} />
}

export function BuyerSaleCard({ sale, imageBaseUrl }: BuyerSaleCardProps) {
  const imageUrl = buildProductImageUrl(imageBaseUrl, sale.representativeImagePath)
  const statusLabel = getSaleStatusLabel(sale.status)
  const statusClass = sale.status === 'ON_SALE'
    ? 'border-[color-mix(in_srgb,var(--color-signal)_28%,white)] text-[var(--color-signal)]'
    : sale.status === 'ENDED'
      ? 'bg-[var(--color-soft-mist)] text-[var(--color-faint-gray)]'
      : 'text-[var(--color-steel)]'
  return <article className="min-w-0">
    <Link className="flex h-full min-w-0 cursor-pointer flex-col border border-[var(--color-concrete-gray)] bg-white hover:border-[var(--color-obsidian)] focus-visible:outline-2 focus-visible:outline-offset-3" to={`/sales/${sale.saleId}`} aria-label={`${sale.name} 상품 상세 보기`}>
      <div className="aspect-square overflow-hidden bg-[var(--color-soft-mist)]"><ProductImage key={imageUrl ?? 'fallback'} name={sale.name} url={imageUrl} /></div>
      <div className="flex flex-1 flex-col p-4 max-md:p-3">
        <span className={`mb-3.5 inline-flex min-h-6 w-fit items-center gap-1.5 rounded-full border border-[var(--color-concrete-gray)] px-2.5 py-0.5 text-[10px] font-semibold tracking-[0.1em] ${statusClass}`}>
          {sale.status === 'ON_SALE' ? <span className="size-1.5 rounded-full bg-[var(--color-signal)]" aria-hidden="true" /> : null}{statusLabel}
        </span>
        <h3 className="line-clamp-2 min-h-12 text-base leading-6 font-medium tracking-[-0.015em] max-[420px]:min-h-0">{sale.name}</h3>
        <p className="mt-auto pt-3 text-[15px] font-semibold">{formatKoreanPrice(sale.price)}</p>
      </div>
    </Link>
  </article>
}
