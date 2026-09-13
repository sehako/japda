import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import {
  buildProductImageUrl,
  formatKoreanPrice,
  formatKoreanSaleDateTime,
  getSaleStatusLabel,
} from '../model/buyerSale.ts'
import type { BuyerSaleProductDetail } from '../model/buyerSale.ts'

interface BuyerSaleDetailProps {
  detail: BuyerSaleProductDetail
  imageBaseUrl: string
}

interface ProductImageProps {
  alt: string
  fallbackLabel: string
  url: string | null
  className: string
  loading?: 'eager' | 'lazy'
}

function ProductImage({ alt, fallbackLabel, url, className, loading = 'lazy' }: ProductImageProps) {
  const [failed, setFailed] = useState(false)
  if (url === null || failed) {
    return <div className={`${className} grid min-h-64 place-items-center bg-[var(--color-soft-mist)] px-6 text-center text-xs tracking-[0.08em] text-[var(--color-faint-gray)]`} role="img" aria-label={fallbackLabel}>이미지 없음</div>
  }
  return <img className={className} src={url} alt={alt} loading={loading} onError={() => setFailed(true)} />
}

export function BuyerSaleDetail({ detail, imageBaseUrl }: BuyerSaleDetailProps) {
  const navigate = useNavigate()
  const [quantity, setQuantity] = useState('1')
  const [quantityError, setQuantityError] = useState(false)
  const representativeImage = detail.images.find(({ isRepresentative }) => isRepresentative)!
  const detailImages = detail.images.filter(({ isRepresentative }) => !isRepresentative)
  const representativeUrl = buildProductImageUrl(imageBaseUrl, representativeImage.path)
  const statusLabel = getSaleStatusLabel(detail.status)
  const canPurchase = detail.status === 'ON_SALE'
  const statusClass = detail.status === 'ON_SALE'
    ? 'border-[color-mix(in_srgb,var(--color-signal)_28%,white)] text-[var(--color-signal)]'
    : detail.status === 'ENDED'
      ? 'border-[var(--color-concrete-gray)] bg-[var(--color-soft-mist)] text-[var(--color-faint-gray)]'
      : 'border-[var(--color-concrete-gray)] text-[var(--color-steel)]'

  return <>
    <Link className="mb-7 inline-flex min-h-10 w-fit items-center gap-2.5 text-[13px] font-medium text-[var(--color-steel)] hover:text-[var(--color-obsidian)] focus-visible:outline-2 focus-visible:outline-offset-3 max-md:mb-[18px]" to="/">
      <svg width="17" height="17" viewBox="0 0 17 17" fill="none" aria-hidden="true"><path d="M10.5 3.5 5.5 8.5l5 5" stroke="currentColor" strokeWidth="1.5" /></svg>
      상품 목록
    </Link>

    <section className="grid grid-cols-[minmax(0,1.45fr)_minmax(360px,0.8fr)] border-y border-[var(--color-obsidian)] max-[900px]:grid-cols-[minmax(0,1.25fr)_minmax(320px,0.8fr)] max-[720px]:block" aria-labelledby="product-name">
      <div className="aspect-[4/3] min-w-0 overflow-hidden bg-[var(--color-soft-mist)] max-[720px]:aspect-square">
        <ProductImage key={representativeUrl ?? 'representative-fallback'} className="block size-full object-cover" url={representativeUrl} alt={detail.name} fallbackLabel={`${detail.name} 대표 이미지 없음`} loading="eager" />
      </div>

      <div className="flex min-w-0 flex-col border-l border-[var(--color-concrete-gray)] p-[clamp(32px,4.5vw,72px)] max-[900px]:p-9 max-[720px]:min-h-[480px] max-[720px]:border-t max-[720px]:border-l-0 max-[720px]:px-0 max-[720px]:pt-8 max-[720px]:pb-0 max-[420px]:min-h-[450px]">
        <span className={`mb-7 inline-flex min-h-[26px] w-fit items-center gap-[7px] rounded-full border px-[11px] py-[3px] text-[11px] font-semibold tracking-[0.12em] max-[720px]:mb-[22px] ${statusClass}`}>
          {detail.status === 'ON_SALE' ? <span className="size-1.5 rounded-full bg-[var(--color-signal)]" aria-hidden="true" /> : null}
          {statusLabel}
        </span>
        <h1 className="max-w-[12ch] break-keep text-[clamp(34px,4vw,56px)] leading-[1.08] font-semibold tracking-[-0.05em] wrap-break-word" id="product-name">{detail.name}</h1>
        <p className="mt-6 text-[22px] font-semibold tracking-[-0.025em]">{formatKoreanPrice(detail.price)}</p>

        <div className="mt-12 border-t border-[var(--color-concrete-gray)] pt-5 max-[900px]:mt-9">
          <span className="mb-[7px] block text-[11px] font-semibold tracking-[0.12em] text-[var(--color-steel)]">SALE PERIOD</span>
          <p className="text-sm leading-[1.7] font-medium">
            <span className="block">{formatKoreanSaleDateTime(detail.startsAt)}</span>
            <span className="block">— {formatKoreanSaleDateTime(detail.endsAt)} KST</span>
          </p>
        </div>

        <form className="mt-auto max-[720px]:mt-12 max-[720px]:mb-8" onSubmit={(event) => {
          event.preventDefault()
          if (!canPurchase) return
          const value = Number(quantity)
          if (!/^[1-9]\d*$/.test(quantity) || !Number.isSafeInteger(value) || value > 2_147_483_647) {
            setQuantityError(true)
            return
          }
          navigate(`/checkout/${detail.saleId}?quantity=${value}`)
        }} noValidate>
          <div className="flex items-center justify-between gap-4 border-t border-[var(--color-concrete-gray)] pt-5">
            <label className="text-sm font-medium" htmlFor="purchase-quantity">구매 수량</label>
            <input className="min-h-11 w-[104px] border border-[var(--color-concrete-gray)] bg-white px-3 text-right focus-visible:outline-2 focus-visible:outline-offset-3" id="purchase-quantity" type="number" inputMode="numeric" min="1" max="2147483647" step="1" value={quantity} aria-invalid={quantityError} aria-describedby={quantityError ? 'purchase-quantity-error' : undefined} onChange={(event) => { setQuantity(event.target.value); setQuantityError(false) }} />
          </div>
          {quantityError ? <p className="mt-2 text-sm text-[var(--color-steel)]" id="purchase-quantity-error" role="alert">구매 수량은 1부터 2,147,483,647까지의 정수여야 합니다.</p> : null}
          <button className="mt-5 min-h-[52px] w-full rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-6 text-base font-medium text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3 disabled:cursor-not-allowed disabled:border-[var(--color-concrete-gray)] disabled:bg-[var(--color-soft-mist)] disabled:text-[var(--color-faint-gray)] disabled:hover:opacity-100" type="submit" disabled={!canPurchase}>구매하기</button>
        </form>
      </div>
    </section>

    <section className="mx-auto mt-[120px] w-full max-w-[1120px] max-[720px]:mt-20" aria-labelledby="details-title">
      <header className="border-b border-[var(--color-obsidian)] pb-5">
        <h2 className="text-xs font-semibold tracking-[0.14em]" id="details-title">PRODUCT DETAILS</h2>
      </header>

      {detail.description !== null ? <div className="mx-auto mt-[72px] mb-24 w-full max-w-[760px] max-[720px]:mt-[52px] max-[720px]:mb-16">
        <p className="wrap-break-word whitespace-pre-line break-keep text-[clamp(16px,2vw,19px)] leading-[1.9] text-[#353535]">{detail.description}</p>
      </div> : null}

      {detailImages.length > 0 ? <div className={detail.description === null ? 'mt-12' : ''}>
        {detailImages.map((image, index) => {
          const alt = `${detail.name} 상세 이미지 ${index + 1}`
          const url = buildProductImageUrl(imageBaseUrl, image.path)
          const spacing = index === 0 ? '' : 'mt-12 max-[720px]:mt-5'
          return <ProductImage key={`${image.displayOrder}-${image.path}-${url ?? 'fallback'}`} className={`${spacing} block h-auto w-full`} url={url} alt={alt} fallbackLabel={`${alt} 없음`} />
        })}
      </div> : null}

      {detail.description === null && detailImages.length === 0 ? <p className="py-20 text-center text-base text-[var(--color-steel)]">추가 상세 정보가 없습니다.</p> : null}
    </section>
  </>
}
