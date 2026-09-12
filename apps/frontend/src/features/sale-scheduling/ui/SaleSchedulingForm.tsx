import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'

import type { ReturnTypeUseSaleScheduling } from '../ui/saleSchedulingTypes.ts'

const SORT_LABELS = { latest: '최신 등록순', oldest: '오래된 등록순', 'name-asc': '상품명 오름차순', 'name-desc': '상품명 내림차순' } as const

function formatNumber(value: number) { return new Intl.NumberFormat('ko-KR').format(value) }
function formatDateTime(value: string) { return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) }

export function SaleSchedulingForm({ scheduling }: { scheduling: ReturnTypeUseSaleScheduling }) {
  const formRef = useRef<HTMLFormElement>(null)
  const errorRef = useRef<HTMLDivElement>(null)
  const locked = scheduling.isSubmitting || !scheduling.windowState.isOpen
  const changingList = scheduling.listStatus === 'changing-sort'

  useEffect(() => {
    const invalid = formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')
    if (invalid) invalid.focus()
    else if (scheduling.formError) errorRef.current?.focus()
  }, [scheduling.fieldErrors, scheduling.formError])

  if (scheduling.createdSale) {
    const sale = scheduling.createdSale
    return <section className="border-y border-[var(--color-obsidian)] py-12 md:py-16" aria-labelledby="sale-complete-heading">
      <p className="mb-2 text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)]">REGISTRATION COMPLETE</p>
      <h2 id="sale-complete-heading" className="text-[clamp(28px,4vw,40px)] leading-tight font-semibold tracking-[-0.03em]">판매 일정이 등록되었습니다.</h2>
      <dl className="mt-9 grid md:grid-cols-3">
        {[['상품', sale.product.name], ['판매 일정 ID', sale.id], ['판매일', sale.saleDate], ['판매 가격', `${formatNumber(sale.price)}원`], ['판매 수량', `${formatNumber(sale.quantity)}개`], ['판매 시간', `${formatDateTime(sale.startsAt)} – ${formatDateTime(sale.endsAt)}`]].map(([label, value]) => <div className="border-t border-[var(--color-concrete-gray)] py-5 md:pr-5" key={String(label)}><dt className="mb-1 text-xs text-[var(--color-steel)]">{label}</dt><dd className="font-medium">{value}</dd></div>)}
      </dl>
      <div className="mt-7 flex justify-end"><Link className="min-h-12 rounded-full border border-[var(--color-concrete-gray)] px-7 py-3 font-medium hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3" to="/seller/products/new">상품 등록으로 이동</Link></div>
    </section>
  }

  const products = scheduling.currentPage?.items ?? []
  return <form ref={formRef} noValidate onSubmit={(event) => { event.preventDefault(); void scheduling.submit() }}>
    <section className={`mb-6 flex gap-3 border border-[var(--color-concrete-gray)] bg-[var(--color-soft-mist)] px-5 py-4 max-md:flex-col ${scheduling.windowState.isOpen ? '' : 'text-[var(--color-steel)]'}`} aria-live="polite">
      <span className={`mt-2 size-2 shrink-0 rounded-full ${scheduling.windowState.isOpen ? 'bg-[var(--color-obsidian)]' : 'bg-[var(--color-faint-gray)]'}`} aria-hidden="true" />
      <div><p className="font-semibold">{scheduling.windowState.title}</p><p className="mt-0.5 text-[13px] text-[var(--color-steel)]">{scheduling.windowState.description}</p></div>
      <p className="ml-auto text-xs font-medium text-[var(--color-signal)] max-md:ml-5">같은 판매일에는 일정 하나만 등록할 수 있습니다.</p>
    </section>
    {scheduling.formError ? <div ref={errorRef} className="mb-5 border-l-[3px] border-[var(--color-signal)] bg-[var(--color-soft-mist)] px-4 py-3.5" role="alert" tabIndex={-1}>{scheduling.formError}</div> : null}
    <div className="border-y border-[var(--color-obsidian)] md:grid md:grid-cols-[minmax(0,1fr)_minmax(360px,0.8fr)]">
      <section className="py-7 md:border-r md:border-[var(--color-concrete-gray)] md:py-9 md:pr-12" aria-labelledby="sale-product-heading">
        <div className="mb-6 flex items-end justify-between gap-5 max-md:flex-col max-md:items-stretch"><h2 id="sale-product-heading" className="text-xl font-semibold">판매 상품</h2><div className="flex items-center gap-2.5 max-md:justify-between"><label className="text-xs font-medium text-[var(--color-steel)]" htmlFor="product-sort">정렬</label><select id="product-sort" className="h-10 min-w-36 border border-[var(--color-concrete-gray)] bg-white px-3 disabled:bg-[var(--color-soft-mist)]" value={scheduling.productSort} disabled={changingList || scheduling.isSubmitting} onChange={(event) => scheduling.changeSort(event.currentTarget.value as keyof typeof SORT_LABELS)}>{Object.entries(SORT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div></div>
        <fieldset className="m-0 border-0 p-0" aria-invalid={Boolean(scheduling.fieldErrors.productId)} aria-describedby="product-choice-help product-page-status product-error"><legend className="mb-2.5 font-medium">상품 <span className="text-[var(--color-signal)]" aria-hidden="true">*</span></legend><p id="product-choice-help" className="sr-only">판매 상품은 한 개만 선택할 수 있습니다.</p>
          <div className={`h-[370px] overflow-auto border ${scheduling.fieldErrors.productId ? 'border-[var(--color-signal)]' : 'border-[var(--color-concrete-gray)]'}`} role="region" aria-label="판매 상품 목록" tabIndex={0}>
            <table className="w-full table-fixed border-collapse"><thead><tr><th className="sticky top-0 z-1 h-[46px] w-16 bg-[var(--color-soft-mist)] px-3 text-center text-[11px] font-semibold tracking-[0.08em] text-[var(--color-steel)]">선택</th><th className="sticky top-0 z-1 h-[46px] bg-[var(--color-soft-mist)] px-3 text-left text-[11px] font-semibold tracking-[0.08em] text-[var(--color-steel)]">상품명</th></tr></thead>
              <tbody>{scheduling.listStatus === 'loading' && products.length === 0 ? <tr><td className="h-56 text-center text-[var(--color-steel)]" colSpan={2}>상품을 불러오는 중...</td></tr> : scheduling.listStatus === 'error' && products.length === 0 ? <tr><td className="h-56 px-5 text-center" colSpan={2}><p>{scheduling.listError}</p><button className="mt-3 underline" type="button" onClick={scheduling.retryList}>다시 시도</button></td></tr> : products.length === 0 ? <tr><td className="h-56 px-5 text-center" colSpan={2}>등록 가능한 상품이 없습니다. <Link className="underline" to="/seller/products/new">상품 등록</Link></td></tr> : products.map((product) => <tr key={product.id} className="border-t border-[var(--color-concrete-gray)]"><td className="h-[54px] px-3 text-center"><input className="size-[18px] accent-[var(--color-obsidian)]" type="checkbox" aria-label={`${product.name} 선택`} checked={scheduling.selectedProduct?.id === product.id} disabled={scheduling.isSubmitting || changingList} onChange={() => scheduling.toggleProduct(product)} /></td><td className="h-[54px] px-3"><p>{product.name}</p></td></tr>)}</tbody>
            </table>
            {changingList ? <p className="sticky bottom-0 bg-white/95 px-4 py-2 text-center text-xs text-[var(--color-steel)]" aria-live="polite">선택한 정렬로 조회 중...</p> : null}
          </div>
          <div className="mt-3.5 flex items-center justify-between gap-4" aria-label="상품 목록 페이지 이동"><span id="product-page-status" className="text-xs text-[var(--color-steel)]" aria-live="polite">{scheduling.pageIndex + 1}페이지 · {products.length}개 상품{scheduling.listStatus === 'loading-next' ? ' · 다음 페이지 조회 중' : ''}</span><div className="flex gap-2"><button className="min-h-9 min-w-15 border border-[var(--color-concrete-gray)] px-3 text-xs disabled:bg-[var(--color-soft-mist)] disabled:text-[var(--color-faint-gray)]" type="button" disabled={scheduling.pageIndex === 0 || changingList || scheduling.isSubmitting} onClick={scheduling.goPrevious}>이전</button><button className="min-h-9 min-w-15 border border-[var(--color-concrete-gray)] px-3 text-xs disabled:bg-[var(--color-soft-mist)] disabled:text-[var(--color-faint-gray)]" type="button" disabled={(!scheduling.currentPage?.nextCursor && !scheduling.hasVisitedNext) || changingList || scheduling.isSubmitting || scheduling.listStatus === 'loading-next'} onClick={() => void scheduling.goNext()}>다음</button></div></div>
          {scheduling.listStatus === 'next-error' || (scheduling.listStatus === 'error' && products.length > 0) ? <div className="mt-3 text-xs text-[var(--color-signal)]" role="alert">{scheduling.listError} <button className="underline" type="button" onClick={scheduling.retryList}>다시 시도</button></div> : null}
          {scheduling.fieldErrors.productId ? <p id="product-error" className="mt-2 text-xs text-[var(--color-signal)]" role="alert">{scheduling.fieldErrors.productId} {scheduling.refreshProductsRequired ? <button className="underline" type="button" onClick={scheduling.refreshProducts}>목록 새로고침</button> : null}</p> : null}
        </fieldset>
        <div className="mt-7 border border-[var(--color-concrete-gray)] bg-[var(--color-soft-mist)] p-5"><p className="mb-3 text-[11px] font-semibold tracking-[0.1em] text-[var(--color-steel)]">SELECTED PRODUCT</p><p className={`text-lg font-semibold ${scheduling.selectedProduct ? '' : 'text-[var(--color-faint-gray)]'}`}>{scheduling.selectedProduct?.name ?? '선택된 상품이 없습니다.'}</p>{scheduling.isPreselectedProduct ? <p className="mt-2 text-xs text-[var(--color-steel)]">방금 등록한 상품이 선택되었습니다. 다른 상품을 선택하면 변경됩니다.</p> : null}</div>
      </section>
      <section className="py-7 md:py-9 md:pl-12" aria-labelledby="sale-schedule-heading"><div className="mb-6"><h2 id="sale-schedule-heading" className="text-xl font-semibold">일정과 재고</h2><p className="mt-1.5 text-[13px] text-[var(--color-steel)]">판매는 등록한 날짜의 00:00부터 24시간 동안 진행됩니다.</p></div>
        <div className="mb-7"><label className="mb-2.5 block font-medium" htmlFor="sale-date">판매일 <span className="text-[var(--color-signal)]" aria-hidden="true">*</span></label><input id="sale-date" className="h-[52px] w-full border border-[var(--color-concrete-gray)] bg-white px-4 disabled:bg-[var(--color-soft-mist)]" type="date" required value={scheduling.saleDate} min={scheduling.windowState.saleDate ?? undefined} max={scheduling.windowState.saleDate ?? undefined} disabled={locked} aria-invalid={Boolean(scheduling.fieldErrors.saleDate)} aria-describedby="sale-date-error" onChange={(event) => scheduling.changeSaleDate(event.currentTarget.value)} />{scheduling.fieldErrors.saleDate ? <p id="sale-date-error" className="mt-2 text-xs text-[var(--color-signal)]" role="alert">{scheduling.fieldErrors.saleDate}</p> : null}</div>
        <div className="grid gap-4 md:grid-cols-2">{([['price', '판매 가격', '35000', '원'], ['quantity', '판매 수량', '100', '개']] as const).map(([field, label, placeholder, unit]) => <div className="mb-7" key={field}><label className="mb-2.5 block font-medium" htmlFor={field}>{label} <span className="text-[var(--color-signal)]" aria-hidden="true">*</span></label><div className="relative"><input id={field} className="h-[52px] w-full border border-[var(--color-concrete-gray)] bg-white px-4 pr-12 disabled:bg-[var(--color-soft-mist)]" type="text" inputMode="numeric" autoComplete="off" required placeholder={placeholder} value={scheduling[field]} disabled={locked} aria-invalid={Boolean(scheduling.fieldErrors[field])} aria-describedby={`${field}-error`} onChange={(event) => field === 'price' ? scheduling.changePrice(event.currentTarget.value) : scheduling.changeQuantity(event.currentTarget.value)} /><span className="pointer-events-none absolute top-1/2 right-4 -translate-y-1/2 text-[var(--color-steel)]">{unit}</span></div>{scheduling.fieldErrors[field] ? <p id={`${field}-error`} className="mt-2 text-xs text-[var(--color-signal)]" role="alert">{scheduling.fieldErrors[field]}</p> : null}</div>)}</div>
        <div className="mt-2 flex justify-end border-t border-[var(--color-concrete-gray)] pt-7"><button className="min-h-12 min-w-44 rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-7 font-medium text-white disabled:border-[var(--color-faint-gray)] disabled:bg-[var(--color-soft-mist)] disabled:text-[var(--color-faint-gray)] max-md:w-full" type="submit" disabled={locked}>{scheduling.isSubmitting ? '등록 중...' : '판매 일정 등록'}</button></div><span className="sr-only" aria-live="polite">{scheduling.isSubmitting ? '등록 중...' : ''}</span>
      </section>
    </div>
  </form>
}
