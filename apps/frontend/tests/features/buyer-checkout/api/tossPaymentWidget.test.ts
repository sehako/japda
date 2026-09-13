import { afterEach, expect, test, vi } from 'vitest'

import { createTossPaymentWidget } from '../../../../src/features/buyer-checkout/api/tossPaymentWidget.ts'

const sdk = vi.hoisted(() => ({
  load: vi.fn(),
  widgets: vi.fn(),
  setAmount: vi.fn(async () => undefined),
  renderMethods: vi.fn(),
  renderAgreement: vi.fn(),
  requestPayment: vi.fn(async () => undefined),
  getSelectedPaymentMethod: vi.fn(async () => ({ code: 'TRANSFER' })),
  destroyMethods: vi.fn(async () => undefined),
  destroyAgreement: vi.fn(async () => undefined),
  methodListener: undefined as undefined | ((value: { code: string }) => void),
  agreementListener: undefined as undefined | ((value: { agreedRequiredTerms: boolean; agreements: Array<{ term: { id: string; required: boolean }; agreed: boolean }> }) => void),
}))

vi.mock('@tosspayments/tosspayments-sdk', () => ({
  ANONYMOUS: '@@ANONYMOUS',
  loadTossPayments: sdk.load,
}))

afterEach(() => {
  vi.clearAllMocks()
  sdk.methodListener = undefined
  sdk.agreementListener = undefined
})

test('비회원 주문서형 위젯의 선택과 필수 약관 상태를 boolean으로 전달한다', async () => {
  sdk.load.mockResolvedValue({ widgets: sdk.widgets })
  sdk.widgets.mockReturnValue({ setAmount: sdk.setAmount, renderPaymentMethods: sdk.renderMethods, renderAgreement: sdk.renderAgreement, requestPayment: sdk.requestPayment })
  sdk.renderMethods.mockResolvedValue({ on: (_event: string, callback: typeof sdk.methodListener) => { sdk.methodListener = callback }, getSelectedPaymentMethod: sdk.getSelectedPaymentMethod, destroy: sdk.destroyMethods })
  sdk.renderAgreement.mockResolvedValue({ on: (_event: string, callback: typeof sdk.agreementListener) => { sdk.agreementListener = callback }, destroy: sdk.destroyAgreement })

  const widget = await createTossPaymentWidget('test_client_key')
  await widget.setAmount({ currency: 'KRW', value: 12000 })
  const methods = await widget.renderPaymentMethods({ selector: '#methods' })
  const agreement = await widget.renderAgreement({ selector: '#agreement' })
  expect(await widget.hasSelectedPaymentMethod()).toBe(true)
  sdk.getSelectedPaymentMethod.mockRejectedValueOnce({ code: 'NOT_SELECTED_PAYMENT_METHOD' })
  expect(await widget.hasSelectedPaymentMethod()).toBe(false)
  const selected = vi.fn()
  const agreed = vi.fn()
  methods.on('paymentMethodSelect', selected)
  agreement.on('agreementStatusChange', agreed)
  sdk.methodListener?.({ code: 'CARD' })
  sdk.agreementListener?.({ agreedRequiredTerms: false, agreements: [{ term: { id: 'required', required: true }, agreed: false }] })
  sdk.agreementListener?.({ agreedRequiredTerms: true, agreements: [{ term: { id: 'required', required: true }, agreed: true }] })

  expect(sdk.load).toHaveBeenCalledWith('test_client_key')
  expect(sdk.widgets).toHaveBeenCalledWith({ customerKey: '@@ANONYMOUS' })
  expect(sdk.setAmount).toHaveBeenCalledWith({ currency: 'KRW', value: 12000 })
  expect(sdk.renderMethods).toHaveBeenCalledWith({ selector: '#methods' })
  expect(sdk.renderAgreement).toHaveBeenCalledWith({ selector: '#agreement' })
  expect(selected).toHaveBeenCalledWith(true)
  expect(agreed.mock.calls.map(([value]) => value)).toEqual([false, true])

  await widget.requestPayment({ orderId: '123e4567-e89b-42d3-a456-426614174000', orderName: '한정판 후디', successUrl: 'https://example.test/payments/toss/success', failUrl: 'https://example.test/payments/toss/fail' })
  expect(sdk.requestPayment).toHaveBeenCalledWith({ orderId: '123e4567-e89b-42d3-a456-426614174000', orderName: '한정판 후디', successUrl: 'https://example.test/payments/toss/success', failUrl: 'https://example.test/payments/toss/fail' })

  await widget.destroy()
  expect(sdk.destroyMethods).toHaveBeenCalledOnce()
  expect(sdk.destroyAgreement).toHaveBeenCalledOnce()
})

test('렌더링이 끝나기 전에 정리하면 늦게 생성된 결제수단 UI도 제거한다', async () => {
  let finishRender!: (value: { on: () => void; destroy: typeof sdk.destroyMethods }) => void
  const pendingRender = new Promise<{ on: () => void; destroy: typeof sdk.destroyMethods }>((resolve) => { finishRender = resolve })
  sdk.load.mockResolvedValue({ widgets: sdk.widgets })
  sdk.widgets.mockReturnValue({ setAmount: sdk.setAmount, renderPaymentMethods: sdk.renderMethods, renderAgreement: sdk.renderAgreement, requestPayment: sdk.requestPayment })
  sdk.renderMethods.mockReturnValue(pendingRender)

  const widget = await createTossPaymentWidget('test_client_key')
  const rendering = widget.renderPaymentMethods({ selector: '#methods' })
  widget.destroy()
  finishRender({ on: () => undefined, destroy: sdk.destroyMethods })

  await expect(rendering).rejects.toThrow('결제 위젯이 정리되었습니다.')
  expect(sdk.destroyMethods).toHaveBeenCalledOnce()
})

test('두 SDK UI의 비동기 정리가 모두 끝날 때까지 destroy 완료를 기다린다', async () => {
  let finishMethods!: () => void
  let finishAgreement!: () => void
  const methodsCleanup = new Promise<void>((resolve) => { finishMethods = resolve })
  const agreementCleanup = new Promise<void>((resolve) => { finishAgreement = resolve })
  sdk.load.mockResolvedValue({ widgets: sdk.widgets })
  sdk.widgets.mockReturnValue({ setAmount: sdk.setAmount, renderPaymentMethods: sdk.renderMethods, renderAgreement: sdk.renderAgreement, requestPayment: sdk.requestPayment })
  sdk.renderMethods.mockResolvedValue({ on: () => undefined, destroy: () => methodsCleanup })
  sdk.renderAgreement.mockResolvedValue({ on: () => undefined, destroy: () => agreementCleanup })

  const widget = await createTossPaymentWidget('test_client_key')
  await widget.renderPaymentMethods({ selector: '#methods' })
  await widget.renderAgreement({ selector: '#agreement' })
  let finished = false
  const cleanup = Promise.resolve(widget.destroy()).then(() => { finished = true })
  await Promise.resolve()
  expect(finished).toBe(false)
  finishMethods()
  await Promise.resolve()
  expect(finished).toBe(false)
  finishAgreement()
  await cleanup
  expect(finished).toBe(true)
})
