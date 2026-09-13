import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '../../../shared/api/apiClient.ts'
import { imageBaseUrl } from '../../../shared/config/env.ts'
import { useBuyerCheckout } from '../hook/useBuyerCheckout.ts'
import { useBuyerPayment } from '../hook/useBuyerPayment.ts'
import { shippingAddressFields, validateShippingAddress } from '../model/buyerCheckout.ts'
import type { CreateShippingAddressRequest, ShippingAddressField } from '../model/buyerCheckout.ts'

const emptyForm = { addressName: '', recipientName: '', phoneNumber: '', postalCode: '', address: '', detailAddress: '', deliveryMessage: '' }
const fieldNames = new Set<ShippingAddressField>(shippingAddressFields.map(({ name }) => name))
const fieldErrorCodes: Record<string, ShippingAddressField> = {
  BUYER_SHIPPING_ADDRESS_NAME_INVALID: 'addressName',
  BUYER_SHIPPING_ADDRESS_RECIPIENT_NAME_INVALID: 'recipientName',
  BUYER_SHIPPING_ADDRESS_PHONE_NUMBER_INVALID: 'phoneNumber',
  BUYER_SHIPPING_ADDRESS_POSTAL_CODE_INVALID: 'postalCode',
  BUYER_SHIPPING_ADDRESS_ADDRESS_INVALID: 'address',
  BUYER_SHIPPING_ADDRESS_DETAIL_ADDRESS_INVALID: 'detailAddress',
  BUYER_SHIPPING_ADDRESS_DELIVERY_MESSAGE_INVALID: 'deliveryMessage',
}

function formatPrice(value: number) {
  return `${new Intl.NumberFormat('ko-KR').format(value)}원`
}

function productImageUrl(path: string): string | null {
  if (!imageBaseUrl) return null
  try {
    const base = new URL(imageBaseUrl)
    if (base.protocol !== 'http:' && base.protocol !== 'https:') return null
    return `${imageBaseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
  } catch {
    return null
  }
}

function ProductImage({ name, path }: { name: string; path: string }) {
  const [failed, setFailed] = useState(false)
  const url = productImageUrl(path)
  return <div className="aspect-square w-[188px] shrink-0 bg-[var(--color-soft-mist)] max-[900px]:w-[150px] max-[420px]:w-[108px]">
    {url && !failed ? <img className="block size-full object-cover" src={url} alt={name} onError={() => setFailed(true)} />
      : <div className="grid size-full place-items-center px-2 text-center text-xs text-[var(--color-faint-gray)]" role="img" aria-label={`${name} 대표 이미지 없음`}>이미지 없음</div>}
  </div>
}

function RegistrationForm({
  isPending,
  onCancel,
  onRetryList,
  onSubmit,
  serverError,
}: {
  isPending: boolean
  onCancel: () => void
  onRetryList: () => void
  onSubmit: (body: CreateShippingAddressRequest) => void
  serverError: unknown
}) {
  const [form, setForm] = useState(emptyForm)
  const [errors, setErrors] = useState<Partial<Record<ShippingAddressField, string>>>({})
  const apiError = serverError instanceof ApiError ? serverError : null
  const serverFields: Partial<Record<ShippingAddressField, string>> = {}
  if (apiError) {
    for (const name of Object.keys(apiError.fieldErrors)) {
      if (fieldNames.has(name as ShippingAddressField)) serverFields[name as ShippingAddressField] = `${shippingAddressFields.find((field) => field.name === name)?.label}을(를) 확인해 주세요.`
    }
    const codeField = fieldErrorCodes[apiError.code ?? '']
    if (codeField && !serverFields[codeField]) serverFields[codeField] = `${shippingAddressFields.find(({ name }) => name === codeField)?.label}을(를) 확인해 주세요.`
    if (apiError.code === 'BUYER_SHIPPING_ADDRESS_NAME_DUPLICATED') serverFields.addressName = '이미 등록된 배송지명입니다.'
  }
  const formError = apiError?.code === 'BUYER_SHIPPING_ADDRESS_LIMIT_EXCEEDED'
    ? '배송지는 최대 10개까지 등록할 수 있습니다.'
    : apiError && Object.keys(serverFields).length === 0
        ? apiError.isNetworkError ? '네트워크 연결을 확인하고 다시 시도해 주세요.' : '배송지를 등록하지 못했습니다. 다시 시도해 주세요.'
        : null

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (isPending) return
    const body: CreateShippingAddressRequest = {
      addressName: form.addressName.trim(), recipientName: form.recipientName.trim(),
      phoneNumber: form.phoneNumber.trim(), postalCode: form.postalCode.trim(),
      address: form.address.trim(), detailAddress: form.detailAddress.trim(),
      deliveryMessage: form.deliveryMessage.trim() || null,
    }
    const nextErrors = validateShippingAddress(body)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length === 0) onSubmit(body)
  }

  return <form className="pt-[26px]" onSubmit={submit} noValidate>
    <h3 className="mb-6 text-base font-semibold">배송지 등록</h3>
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 max-[420px]:grid-cols-1">
      {shippingAddressFields.map(({ name, label, maxLength, required }) => {
        const error = errors[name] ?? serverFields[name]
        return <label className={`block min-w-0 ${name === 'address' || name === 'detailAddress' || name === 'deliveryMessage' ? 'col-span-2 max-[420px]:col-span-1' : ''}`} key={name}>
          <span className="mb-[7px] block text-[13px] font-medium">{label}</span>
          {name === 'deliveryMessage'
            ? <textarea className="min-h-[82px] w-full resize-y border border-[var(--color-concrete-gray)] bg-white px-[13px] py-[11px] focus-visible:outline-2 focus-visible:outline-offset-3" name={name} aria-label={label} maxLength={maxLength} value={form[name]} placeholder="문 앞에 놓아주세요" aria-invalid={!!error} aria-describedby={error ? `${name}-error` : undefined} onChange={(event) => { setForm({ ...form, [name]: event.target.value }); setErrors({ ...errors, [name]: undefined }) }} />
            : <input className="min-h-[46px] w-full border border-[var(--color-concrete-gray)] bg-white px-[13px] py-[11px] focus-visible:outline-2 focus-visible:outline-offset-3" name={name} aria-label={label} type={name === 'phoneNumber' ? 'tel' : 'text'} autoComplete={name === 'recipientName' ? 'name' : name === 'phoneNumber' ? 'tel' : name === 'postalCode' ? 'postal-code' : name === 'address' ? 'street-address' : name === 'detailAddress' ? 'address-line2' : undefined} maxLength={maxLength} required={required} value={form[name]} aria-invalid={!!error} aria-describedby={error ? `${name}-error` : undefined} onChange={(event) => { setForm({ ...form, [name]: event.target.value }); setErrors({ ...errors, [name]: undefined }) }} />}
          {error ? <span className="mt-1 block text-xs text-[var(--color-steel)]" id={`${name}-error`} role="alert">{error}</span> : null}
        </label>
      })}
    </div>
    {formError ? <p className="mt-4 text-sm text-[var(--color-steel)]" role="alert">{formError}</p> : null}
    {apiError?.code === 'BUYER_SHIPPING_ADDRESS_LIMIT_EXCEEDED' || apiError?.code === 'BUYER_SHIPPING_ADDRESS_NAME_DUPLICATED'
      ? <button className="mt-3 text-sm underline" type="button" onClick={onRetryList}>목록 다시 시도</button> : null}
    <div className="mt-7 flex justify-end gap-2.5">
      <button className="min-h-[42px] rounded-full border border-[var(--color-concrete-gray)] px-[22px] font-medium" type="button" onClick={onCancel} disabled={isPending}>등록 취소</button>
      <button className="min-h-[42px] rounded-full bg-[var(--color-obsidian)] px-[22px] font-medium text-white disabled:opacity-50" type="submit" disabled={isPending}>{isPending ? '등록 중' : '등록하기'}</button>
    </div>
  </form>
}

export function BuyerCheckoutContent({ saleId, quantity, buyerId }: { saleId: number; quantity: number; buyerId: number }) {
  const { query, registration, refresh } = useBuyerCheckout(saleId, quantity, buyerId)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [createdId, setCreatedId] = useState<number | null>(null)

  async function register(body: CreateShippingAddressRequest) {
    try {
      const created = await registration.mutateAsync(body)
      setCreatedId(created.shippingAddressId)
      setFormOpen(false)
      await refresh()
    } catch {
      // 오류는 mutation 또는 조회 상태에서 사용자에게 표시한다.
    }
  }

  const data = query.data
  const createdAddress = data?.shippingAddresses.find(({ shippingAddressId }) => shippingAddressId === createdId)
  const effectiveSelection = selectedId ?? createdAddress?.shippingAddressId ?? null
  const selectedAddress = data?.shippingAddresses.find(({ shippingAddressId }) => shippingAddressId === effectiveSelection) ?? null
  const payment = useBuyerPayment({ checkout: query.isSuccess ? data ?? null : null, selectedAddress: query.isSuccess ? selectedAddress : null, buyerId, blocked: query.isFetching || registration.isPending || formOpen, refresh })
  const notFound = query.error instanceof ApiError && query.error.status === 404 && query.error.code === 'ORDER_SALE_NOT_FOUND'

  let content: React.ReactNode
  if (createdId !== null && (query.isFetching || query.isPending)) {
    content = <p className="border-y border-[var(--color-concrete-gray)] py-20 text-center" role="status">배송지 목록을 새로 불러오는 중입니다.</p>
  } else if (createdId !== null && query.isError) {
    content = <div className="border-y border-[var(--color-concrete-gray)] py-20 text-center" role="alert"><p>배송지 등록은 완료됐지만 목록을 불러오지 못했습니다.</p><button className="mt-5 rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" type="button" onClick={() => void query.refetch()}>목록 다시 시도</button></div>
  } else if (query.isPending) {
    content = <div className="grid gap-12 border-y border-[var(--color-concrete-gray)] py-20 text-center md:grid-cols-2" role="status" aria-live="polite"><p>상품 정보를 불러오는 중입니다.</p><p>배송지 정보를 불러오는 중입니다.</p><span className="sr-only">체크아웃 정보를 불러오는 중입니다.</span></div>
  } else if (notFound) {
    content = <div className="border-y border-[var(--color-concrete-gray)] py-20 text-center" role="alert"><p className="text-xl font-semibold">판매 상품을 찾을 수 없습니다.</p><Link className="mt-6 inline-flex rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" to="/">상품 목록</Link></div>
  } else if (query.isError) {
    content = <div className="border-y border-[var(--color-concrete-gray)] py-20 text-center" role="alert"><p className="text-xl font-semibold">체크아웃 정보를 불러오지 못했습니다.</p><button className="mt-6 rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" type="button" onClick={() => void query.refetch()}>다시 시도</button></div>
  } else if (!data) {
    content = null
  } else {
    content = <>
      <Link className="mb-[42px] inline-flex min-h-10 items-center gap-2.5 text-[13px] font-medium text-[var(--color-steel)] hover:text-[var(--color-obsidian)] max-[720px]:mb-6" to={`/sales/${saleId}`}><svg width="17" height="17" viewBox="0 0 17 17" fill="none" aria-hidden="true"><path d="M10.5 3.5 5.5 8.5l5 5" stroke="currentColor" strokeWidth="1.5" /></svg>상품으로 돌아가기</Link>
      <p className="mb-[7px] text-[11px] font-semibold tracking-[0.16em]">주문 전 확인</p>
      <h1 className="text-[clamp(34px,4vw,48px)] leading-[1.2] font-semibold tracking-[-0.055em]">체크아웃</h1>
      <div className="mt-[54px] grid grid-cols-[minmax(0,1fr)_340px] items-start gap-[clamp(48px,6vw,96px)] max-[1000px]:block max-[720px]:mt-[38px]">
        <div className="min-w-0">
          <section aria-labelledby="product-heading">
            <div className="flex items-baseline justify-between border-b border-[var(--color-obsidian)] pb-[17px]"><h2 className="text-xl font-semibold tracking-[-0.025em]" id="product-heading">상품 정보</h2><span className="text-xs text-[var(--color-steel)]">01</span></div>
            <div className="flex gap-6 border-b border-[var(--color-concrete-gray)] py-[26px] max-[420px]:gap-4 max-[420px]:py-5"><ProductImage key={data.representativeImagePath} name={data.productName} path={data.representativeImagePath} /><div className="flex min-w-0 flex-1 flex-col"><p className="mb-2 text-[11px] font-semibold tracking-[0.13em] text-[var(--color-steel)]">한정 판매</p><h3 className="break-keep text-[21px] leading-[1.4] font-semibold tracking-[-0.025em] max-[420px]:text-[17px]">{data.productName}</h3><p className="mt-2 text-base font-medium max-[420px]:text-sm">{formatPrice(data.unitPrice)}</p><p className="mt-auto flex justify-between gap-4 text-[13px] text-[var(--color-steel)] max-[420px]:mt-3"><span>구매 수량</span><strong className="text-sm font-semibold text-[var(--color-obsidian)]">{quantity}개</strong></p></div></div>
          </section>
          <section className="mt-16 max-[720px]:mt-[52px]" aria-labelledby="shipping-heading">
            <div className="flex items-baseline justify-between border-b border-[var(--color-obsidian)] pb-[17px]"><h2 className="text-xl font-semibold tracking-[-0.025em]" id="shipping-heading">배송지</h2><span className="text-xs text-[var(--color-steel)]">02</span></div>
            {data.shippingAddresses.length > 0 ? <ul className="mt-0 list-none p-0">{data.shippingAddresses.map((address) => <li className="mt-3 first:mt-0" key={address.shippingAddressId}><label className="block cursor-pointer border border-[var(--color-concrete-gray)] p-[22px_20px] has-checked:border-[var(--color-obsidian)] has-checked:outline has-checked:outline-1 has-checked:-outline-offset-1 has-checked:outline-[var(--color-obsidian)] hover:bg-[#fafafa]"><span className="mb-[13px] flex items-center gap-[11px]"><input className="size-[18px] accent-[var(--color-obsidian)]" type="radio" name="shipping-address" checked={effectiveSelection === address.shippingAddressId} onChange={() => setSelectedId(address.shippingAddressId)} /><span className="text-base font-semibold">{address.addressName}</span></span><span className="block pl-[29px] text-sm leading-[1.7] text-[#353535]"><span className="block font-medium">{address.recipientName} · {address.phoneNumber}</span><span className="block">{address.postalCode} · {address.address}</span><span className="block">{address.detailAddress}</span>{address.deliveryMessage ? <span className="mt-[7px] block text-[13px] text-[var(--color-steel)]">{address.deliveryMessage}</span> : null}</span></label></li>)}</ul>
              : formOpen ? <RegistrationForm isPending={registration.isPending} onCancel={() => { setFormOpen(false); registration.reset() }} onRetryList={() => { setFormOpen(false); registration.reset(); void query.refetch() }} onSubmit={(body) => void register(body)} serverError={registration.error} />
                : <div className="border-b border-[var(--color-concrete-gray)] px-5 py-[58px] text-center"><p className="mb-[22px] text-[15px] text-[var(--color-steel)]">등록된 배송지가 없습니다.</p><button className="min-h-[42px] rounded-full bg-[var(--color-obsidian)] px-[22px] font-medium text-white" type="button" onClick={() => setFormOpen(true)}>배송지 등록</button></div>}
          </section>
          <section className="mt-16 max-[720px]:mt-[52px]" aria-labelledby="payment-heading">
            <div className="flex items-baseline justify-between border-b border-[var(--color-obsidian)] pb-[17px]"><h2 className="text-xl font-semibold" id="payment-heading">결제수단</h2><span className="text-xs text-[var(--color-steel)]">03</span></div>
            <div id="buyer-payment-methods" className="min-h-16 border-b border-[var(--color-concrete-gray)]" />
            <div id="buyer-payment-agreement" className="min-h-16" />
          </section>
        </div>
        <aside className="sticky top-9 border-t border-[var(--color-obsidian)] max-[1000px]:static max-[1000px]:mt-16" aria-labelledby="summary-heading"><h2 className="border-b border-[var(--color-concrete-gray)] py-[18px_22px] text-xl font-semibold" id="summary-heading">금액 정보</h2><div className="flex justify-between gap-4 pt-[21px]"><span className="text-[var(--color-steel)]">상품 금액</span><span>{formatPrice(data.totalPrice)}</span></div><div className="flex justify-between gap-4 pt-[21px]"><span className="text-[var(--color-steel)]">수량</span><span>{quantity}개</span></div><div className="mt-[25px] flex items-baseline justify-between gap-4 border-t border-[var(--color-obsidian)] pt-[22px]"><span className="text-[15px] font-semibold">예상 총액</span><strong className="whitespace-nowrap text-[27px] font-semibold tracking-[-0.035em]">{formatPrice(data.totalPrice)}</strong></div><button className="mt-8 min-h-[52px] w-full rounded-full bg-[var(--color-obsidian)] px-6 text-base font-medium text-white disabled:cursor-not-allowed disabled:bg-[var(--color-soft-mist)] disabled:text-[var(--color-faint-gray)]" type="button" disabled={!payment.canPay} onClick={() => void payment.pay()}>결제하기</button><div className={`mt-3 min-h-5 text-sm ${payment.hintIsError ? 'font-medium text-[var(--color-signal)]' : 'text-[var(--color-steel)]'}`} role="status" aria-live="polite">{payment.hint}</div>{payment.status === 'sdk-error' ? <button className="mt-2 text-sm underline" type="button" onClick={payment.retryWidget}>결제수단 다시 시도</button> : null}{payment.status === 'changed' ? <button className="mt-2 text-sm underline" type="button" onClick={() => void payment.refreshCheckout()}>체크아웃 다시 조회</button> : null}{payment.status === 'expired' || payment.status === 'invalid' ? <button className="mt-2 text-sm underline" type="button" onClick={payment.retryOrder}>새 주문 시도</button> : null}<p className="mt-4 text-xs leading-relaxed text-[var(--color-steel)]">테스트 결제입니다. 인증 후에도 실제 결제는 완료되지 않습니다.</p></aside>
      </div>
    </>
  }

  return content
}
