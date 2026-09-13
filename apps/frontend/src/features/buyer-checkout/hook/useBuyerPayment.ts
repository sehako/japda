import { useEffect, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'

import { ApiError } from '../../../shared/api/apiClient.ts'
import { apiBaseUrl, tossPaymentPreviewConfig } from '../../../shared/config/env.ts'
import { createBuyerOrder } from '../api/buyerOrderApi.ts'
import { createTossPaymentWidget } from '../api/tossPaymentWidget.ts'
import type { TossPaymentWidget } from '../api/tossPaymentWidget.ts'
import type { BuyerCheckout, ShippingAddress } from '../model/buyerCheckout.ts'
import { checkBuyerOrder, createBuyerOrderRequest } from '../model/buyerOrder.ts'
import type { BuyerOrder, CreateBuyerOrderRequest } from '../model/buyerOrder.ts'

type PaymentStatus = 'loading' | 'ready' | 'ordering' | 'processing' | 'order-error' | 'changed' | 'expired' | 'invalid' | 'sdk-error'
type Attempt = { fingerprint: string; key: string; order?: BuyerOrder }

function orderErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '주문을 준비하지 못했습니다. 다시 시도해 주세요.'
  if (error.isNetworkError) return '네트워크 연결을 확인하고 다시 시도해 주세요.'
  if (error.code === 'ORDER_SALE_NOT_OPEN') return '판매가 종료됐거나 시작 전입니다. 상품 정보를 다시 확인해 주세요.'
  if (error.code === 'ORDER_QUANTITY_UNAVAILABLE') return '재고가 부족합니다. 상품 정보를 다시 확인해 주세요.'
  if (error.code === 'ORDER_IDEMPOTENCY_CONFLICT') return '주문 요청이 변경됐습니다. 다시 시도해 주세요.'
  return '주문을 준비하지 못했습니다. 다시 시도해 주세요.'
}

function missingRequirements(selectedAddress: ShippingAddress | null, methodSelected: boolean, agreed: boolean | null): string | null {
  const messages: string[] = []
  if (!selectedAddress) messages.push('배송지를 선택해 주세요.')
  if (!methodSelected) messages.push('결제수단을 선택해 주세요.')
  if (agreed === false) messages.push('필수 약관에 동의해 주세요.')
  return messages.length > 0 ? messages.join(' ') : null
}

export function useBuyerPayment({ checkout, selectedAddress, buyerId, blocked, refresh }: {
  checkout: BuyerCheckout | null
  selectedAddress: ShippingAddress | null
  buyerId: number
  blocked: boolean
  refresh: () => Promise<unknown>
}) {
  const [status, setStatus] = useState<PaymentStatus>('loading')
  const [message, setMessage] = useState<string | null>(null)
  const [retryIndex, setRetryIndex] = useState(0)
  const widgetRef = useRef<TossPaymentWidget | null>(null)
  const cleanupRef = useRef<Promise<void>>(Promise.resolve())
  const selectionRef = useRef<{ method: boolean; agreed: boolean | null }>({ method: false, agreed: null })
  const attemptRef = useRef<Attempt | null>(null)
  const lockedRef = useRef(false)
  const mutation = useMutation({ mutationFn: ({ body, key }: { body: CreateBuyerOrderRequest; key: string }) => createBuyerOrder(body, buyerId, key, { baseUrl: apiBaseUrl }) })
  const checkoutSaleId = checkout?.saleId
  const checkoutQuantity = checkout?.quantity
  const checkoutProductName = checkout?.productName
  const checkoutTotalPrice = checkout?.totalPrice
  const addressId = selectedAddress?.shippingAddressId

  useEffect(() => { attemptRef.current = null }, [checkoutSaleId, checkoutQuantity, checkoutProductName, checkoutTotalPrice, addressId])

  useEffect(() => {
    if (!checkout || !tossPaymentPreviewConfig.valid || blocked) return
    let disposed = false
    let widget: TossPaymentWidget | null = null
    const previousCleanup = cleanupRef.current
    async function initialize() {
      try {
        await previousCleanup
        if (disposed) return
        if (!checkout || !tossPaymentPreviewConfig.valid) return
        setStatus('loading')
        selectionRef.current = { method: false, agreed: null }
        setMessage(null)
        widget = await createTossPaymentWidget(tossPaymentPreviewConfig.clientKey)
        if (disposed) { await widget.destroy(); return }
        await widget.setAmount({ currency: 'KRW', value: checkout.totalPrice })
        const [methods, agreement] = await Promise.all([
          widget.renderPaymentMethods({ selector: '#buyer-payment-methods' }),
          widget.renderAgreement({ selector: '#buyer-payment-agreement' }),
        ])
        if (disposed) { await widget.destroy(); return }
        methods.on('paymentMethodSelect', (selected) => { if (!disposed) selectionRef.current.method = selected })
        agreement.on('agreementStatusChange', (agreed) => { if (!disposed) selectionRef.current.agreed = agreed })
        widgetRef.current = widget
        setStatus('ready')
      } catch {
        await widget?.destroy()
        if (!disposed) { setStatus('sdk-error'); setMessage('결제수단을 준비하지 못했습니다. 다시 시도해 주세요.') }
      }
    }
    const initialization = Promise.resolve().then(initialize)
    return () => {
      disposed = true
      widgetRef.current = null
      cleanupRef.current = initialization.then(async () => { await widget?.destroy() }).catch(() => undefined)
    }
  }, [checkout, checkoutSaleId, checkoutQuantity, checkoutProductName, checkoutTotalPrice, retryIndex, blocked])

  async function pay() {
    if (lockedRef.current || blocked || !checkout || !widgetRef.current || (status !== 'ready' && status !== 'order-error') || !tossPaymentPreviewConfig.valid) return
    lockedRef.current = true
    const widget = widgetRef.current
    let stage: 'selection' | 'order' | 'sdk' = 'selection'
    try {
      const methodSelected = await widget.hasSelectedPaymentMethod()
      if (widgetRef.current !== widget) return
      const requirementError = missingRequirements(selectedAddress, methodSelected, selectionRef.current.agreed)
      if (requirementError || !selectedAddress) {
        setMessage(requirementError)
        return
      }
      setMessage(null)
      const body = createBuyerOrderRequest(checkout, selectedAddress)
      const fingerprint = JSON.stringify(body)
      let attempt = attemptRef.current
      if (!attempt || attempt.fingerprint !== fingerprint) {
        attempt = { fingerprint, key: crypto.randomUUID() }
        attemptRef.current = attempt
      }
      stage = 'order'
      let order = attempt.order
      if (!order) {
        setStatus('ordering')
        order = await mutation.mutateAsync({ body, key: attempt.key })
        if (attemptRef.current !== attempt) { setStatus('ready'); return }
        attempt = { ...attempt, order }
        attemptRef.current = attempt
      }
      const check = checkBuyerOrder(order, checkout)
      if (check !== 'valid') {
        if (check !== 'expired') attemptRef.current = null
        setStatus(check)
        setMessage(check === 'changed' ? '상품 정보가 변경됐습니다. 체크아웃 정보를 다시 불러와 주세요.' : check === 'expired' ? '주문 유효 시간이 지났습니다. 새 주문을 시도해 주세요.' : '주문 정보를 확인할 수 없습니다. 다시 시도해 주세요.')
        return
      }
      setStatus('processing')
      stage = 'sdk'
      await widget.setAmount({ currency: 'KRW', value: order.totalPrice })
      if (attemptRef.current !== attempt) { setStatus('ready'); return }
      if (checkBuyerOrder(order, checkout) === 'expired') {
        attemptRef.current = null
        setStatus('expired')
        setMessage('주문 유효 시간이 지났습니다. 새 주문을 시도해 주세요.')
        return
      }
      if (widgetRef.current !== widget) { setStatus('ready'); return }
      const latestMethodSelected = await widget.hasSelectedPaymentMethod()
      const latestRequirementError = missingRequirements(selectedAddress, latestMethodSelected, selectionRef.current.agreed)
      if (latestRequirementError) {
        setStatus('ready')
        setMessage(latestRequirementError)
        return
      }
      const query = `saleId=${checkout.saleId}&quantity=${checkout.quantity}`
      const origin = window.location.origin
      await widget.requestPayment({ orderId: order.paymentOrderId, orderName: order.productName, successUrl: `${origin}/payments/toss/success?${query}`, failUrl: `${origin}/payments/toss/fail?${query}` })
    } catch (error) {
      const sdkCode = typeof error === 'object' && error !== null && 'code' in error ? error.code : null
      if (stage === 'sdk' && sdkCode === 'USER_CANCEL') {
        setStatus('ready')
        setMessage(null)
      } else if (stage === 'sdk' && (sdkCode === 'NEED_AGREEMENT_WITH_REQUIRED_TERMS' || sdkCode === 'NOT_SELECTED_PAYMENT_METHOD')) {
        setStatus('ready')
        setMessage(sdkCode === 'NEED_AGREEMENT_WITH_REQUIRED_TERMS' ? '필수 약관에 동의해 주세요.' : '결제수단을 선택해 주세요.')
      } else if (stage !== 'order') {
        void widget.destroy().catch(() => undefined)
        widgetRef.current = null
        setStatus('sdk-error')
        setMessage('결제를 요청하지 못했습니다. 결제수단을 다시 준비해 주세요.')
      } else {
        if (error instanceof ApiError && error.status && error.status < 500) attemptRef.current = null
        setStatus('order-error')
        setMessage(orderErrorMessage(error))
      }
    } finally {
      lockedRef.current = false
    }
  }

  function retryWidget() { setRetryIndex((value) => value + 1) }
  function retryOrder() { attemptRef.current = null; setStatus('ready'); setMessage(null) }
  async function refreshCheckout() { attemptRef.current = null; setStatus('loading'); await refresh() }

  const canPay = tossPaymentPreviewConfig.valid && !blocked && (status === 'ready' || status === 'order-error')
  const hint = !tossPaymentPreviewConfig.valid ? tossPaymentPreviewConfig.error
    : status === 'loading' ? '결제수단을 준비하는 중입니다.'
      : status === 'ordering' ? '주문을 준비하는 중입니다.'
        : status === 'processing' ? '결제 진행 중입니다.'
          : message
  const hintIsError = !tossPaymentPreviewConfig.valid || (message !== null && hint === message)
  return { canPay, hint, hintIsError, status, pay, retryWidget, retryOrder, refreshCheckout, previewEnabled: tossPaymentPreviewConfig.valid }
}
