import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'

import { BuyerCheckoutContent } from '../../../../src/features/buyer-checkout/ui/BuyerCheckoutContent.tsx'

const { createWidget, listeners, setAmount, hasSelectedPaymentMethod, requestPayment, destroy } = vi.hoisted(() => ({
  createWidget: vi.fn(),
  listeners: {} as Record<string, (selected: boolean) => void>,
  setAmount: vi.fn(async () => undefined),
  hasSelectedPaymentMethod: vi.fn(async () => true),
  requestPayment: vi.fn(async () => undefined),
  destroy: vi.fn(async () => undefined),
}))

vi.mock('../../../../src/shared/config/env.ts', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../../../src/shared/config/env.ts')>(),
  tossPaymentPreviewConfig: { valid: true, clientKey: 'test_key' },
}))
vi.mock('../../../../src/features/buyer-checkout/api/tossPaymentWidget.ts', () => ({ createTossPaymentWidget: createWidget }))

const address = { shippingAddressId: 7, addressName: '집', recipientName: '홍길동', phoneNumber: '010', postalCode: '06236', address: '서울', detailAddress: '101호', deliveryMessage: '문 앞' }
const checkout = { saleId: 11, productName: '한정판 후디', representativeImagePath: '/main.webp', quantity: 3, unitPrice: 120000, totalPrice: 360000, shippingAddresses: [address] }
const order = { orderId: 1, paymentOrderId: '123e4567-e89b-42d3-a456-426614174000', status: 'PENDING_PAYMENT', productName: '한정판 후디', quantity: 3, unitPrice: 120000, totalPrice: 360000, expiresAt: '2099-01-01T00:00:00Z' }

function show() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter><BuyerCheckoutContent saleId={11} quantity={3} buyerId={42} /></MemoryRouter></QueryClientProvider>)
}

test('체크아웃은 승인 결과를 확인하는 테스트 결제임을 안내한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json(checkout)))
  readyWidget()
  show()

  expect(await screen.findByText('테스트 결제입니다. 결제 인증 후 승인 결과를 확인합니다.')).toBeInTheDocument()
})

function readyWidget() {
  createWidget.mockResolvedValue({ setAmount, renderPaymentMethods: async () => ({ on: (name: string, listener: (selected: boolean) => void) => { listeners[name] = listener } }), renderAgreement: async () => ({ on: (name: string, listener: (selected: boolean) => void) => { listeners[name] = listener } }), hasSelectedPaymentMethod, requestPayment, destroy })
}

async function choosePayment() {
  await waitFor(() => expect(listeners.agreementStatusChange).toBeDefined())
  fireEvent.click(screen.getByRole('radio', { name: /집/ }))
  act(() => { listeners.paymentMethodSelect(true); listeners.agreementStatusChange(true) })
  return screen.findByRole('button', { name: '결제하기' })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.clearAllMocks()
  hasSelectedPaymentMethod.mockResolvedValue(true)
  destroy.mockImplementation(async () => undefined)
  for (const key of Object.keys(listeners)) delete listeners[key]
})

test('결제 버튼을 누른 순간 빠진 항목만 안내하고 선택 변경만으로 문구를 갱신하지 않는다', async () => {
  const fetcher = vi.fn(async () => Response.json(checkout))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  const button = await screen.findByRole('button', { name: '결제하기' })
  await waitFor(() => expect(listeners.paymentMethodSelect).toBeDefined())
  act(() => listeners.agreementStatusChange(false))
  hasSelectedPaymentMethod.mockResolvedValueOnce(false).mockResolvedValueOnce(false)
  expect(button).toBeEnabled()
  expect(screen.queryByText('배송지를 선택해 주세요.')).not.toBeInTheDocument()
  fireEvent.click(button)
  expect(await screen.findByText('배송지를 선택해 주세요. 결제수단을 선택해 주세요. 필수 약관에 동의해 주세요.')).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveClass('text-[var(--color-signal)]')
  fireEvent.click(screen.getByRole('radio', { name: /집/ }))
  expect(screen.getByText('배송지를 선택해 주세요. 결제수단을 선택해 주세요. 필수 약관에 동의해 주세요.')).toBeInTheDocument()
  fireEvent.click(button)
  expect(await screen.findByText('결제수단을 선택해 주세요. 필수 약관에 동의해 주세요.')).toBeInTheDocument()
  act(() => listeners.paymentMethodSelect(true))
  expect(screen.getByText('결제수단을 선택해 주세요. 필수 약관에 동의해 주세요.')).toBeInTheDocument()
  fireEvent.click(button)
  expect(await screen.findByText('필수 약관에 동의해 주세요.')).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveClass('text-[var(--color-signal)]')
  act(() => listeners.agreementStatusChange(true))
  expect(screen.getByText('필수 약관에 동의해 주세요.')).toBeInTheDocument()
  expect(fetcher).toHaveBeenCalledTimes(1)
})

test('렌더링 때 이미 선택된 결제수단과 동의 상태는 이벤트가 없어도 결제를 요청한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  await waitFor(() => expect(listeners.agreementStatusChange).toBeDefined())
  fireEvent.click(screen.getByRole('radio', { name: /집/ }))
  fireEvent.click(screen.getByRole('button', { name: '결제하기' }))
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1))
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(screen.queryByText(/결제수단을 선택해 주세요|필수 약관에 동의해 주세요/)).not.toBeInTheDocument()
})

test('SDK가 필수 약관 미동의를 알리면 위젯과 주문을 유지하며 다음 클릭을 허용한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  requestPayment.mockRejectedValueOnce({ code: 'NEED_AGREEMENT_WITH_REQUIRED_TERMS' })
  show()
  await waitFor(() => expect(listeners.agreementStatusChange).toBeDefined())
  fireEvent.click(screen.getByRole('radio', { name: /집/ }))
  const button = screen.getByRole('button', { name: '결제하기' })
  fireEvent.click(button)
  expect(await screen.findByText('필수 약관에 동의해 주세요.')).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveClass('text-[var(--color-signal)]')
  expect(button).toBeEnabled()
  expect(destroy).not.toHaveBeenCalled()
  act(() => listeners.agreementStatusChange(true))
  fireEvent.click(button)
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(2))
  expect(fetcher).toHaveBeenCalledTimes(2)
})

test('사용자가 결제창을 닫으면 오류 없이 위젯과 주문을 유지해 같은 주문으로 다시 결제한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  requestPayment.mockRejectedValueOnce({ code: 'USER_CANCEL' })
  show()
  const button = await choosePayment()
  fireEvent.click(button)
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1))
  await waitFor(() => expect(button).toBeEnabled())
  expect(screen.queryByText('결제를 요청하지 못했습니다. 결제수단을 다시 준비해 주세요.')).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveClass('text-[var(--color-steel)]')
  expect(screen.queryByRole('button', { name: '결제수단 다시 시도' })).not.toBeInTheDocument()
  expect(destroy).not.toHaveBeenCalled()

  fireEvent.click(button)
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(2))
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(createWidget).toHaveBeenCalledTimes(1)
  expect(requestPayment.mock.calls[1][0].orderId).toBe(requestPayment.mock.calls[0][0].orderId)
})

test('확정 금액으로 다시 설정한 뒤 서버 주문 식별자로 결제를 요청한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  await waitFor(() => expect(listeners.agreementStatusChange).toBeDefined())
  fireEvent.click(screen.getByRole('radio', { name: /집/ }))
  listeners.paymentMethodSelect(true)
  listeners.agreementStatusChange(true)
  fireEvent.click(await screen.findByRole('button', { name: '결제하기' }))
  await waitFor(() => expect(requestPayment).toHaveBeenCalledWith({ orderId: order.paymentOrderId, orderName: order.productName, successUrl: 'http://localhost:3000/payments/toss/success?saleId=11&quantity=3', failUrl: 'http://localhost:3000/payments/toss/fail?saleId=11&quantity=3' }))
  expect(setAmount).toHaveBeenCalledTimes(2)
  expect(setAmount).toHaveBeenLastCalledWith({ currency: 'KRW', value: 360000 })
  const init = fetcher.mock.calls[1]
  expect(JSON.parse(String(init[1]?.body))).toEqual({ saleId: 11, quantity: 3, shippingAddress: { recipientName: '홍길동', phoneNumber: '010', postalCode: '06236', address: '서울', detailAddress: '101호', deliveryMessage: '문 앞' } })
})

test('주문 네트워크 오류 재시도에는 동일한 멱등성 키를 쓰고 중복 클릭은 무시한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockRejectedValueOnce(new TypeError('network')).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  const button = await choosePayment()
  fireEvent.click(button)
  fireEvent.click(button)
  expect(await screen.findByText('네트워크 연결을 확인하고 다시 시도해 주세요.')).toBeInTheDocument()
  expect(fetcher).toHaveBeenCalledTimes(2)
  fireEvent.click(button)
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1))
  const first = new Headers(fetcher.mock.calls[1][1]?.headers).get('Idempotency-Key')
  const second = new Headers(fetcher.mock.calls[2][1]?.headers).get('Idempotency-Key')
  expect(second).toBe(first)
})

test('확정 주문 금액이 예상 금액과 다르면 결제를 중단하고 재조회 경로를 제공한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json({ ...order, totalPrice: 350000 })).mockResolvedValueOnce(Response.json({ ...checkout, totalPrice: 350000 }))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  fireEvent.click(await choosePayment())
  expect(await screen.findByText('상품 정보가 변경됐습니다. 체크아웃 정보를 다시 불러와 주세요.')).toBeInTheDocument()
  expect(requestPayment).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '체크아웃 다시 조회' }))
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3))
})

test('SDK 요청 오류 후 유효한 기존 주문으로 다시 요청한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  requestPayment.mockRejectedValueOnce(new Error('SDK'))
  show()
  fireEvent.click(await choosePayment())
  expect(await screen.findByText('결제를 요청하지 못했습니다. 결제수단을 다시 준비해 주세요.')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '결제수단 다시 시도' }))
  fireEvent.click(await choosePayment())
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(2))
  expect(fetcher).toHaveBeenCalledTimes(2)
})

test('기존 결제 UI의 비동기 정리가 끝난 뒤 새 위젯을 렌더한다', async () => {
  let finishDestroy!: () => void
  const pendingDestroy = new Promise<void>((resolve) => { finishDestroy = resolve })
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  destroy.mockReturnValue(pendingDestroy)
  requestPayment.mockRejectedValueOnce(new Error('SDK'))
  show()
  fireEvent.click(await choosePayment())
  expect(await screen.findByText('결제를 요청하지 못했습니다. 결제수단을 다시 준비해 주세요.')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '결제수단 다시 시도' }))
  await act(async () => { await Promise.resolve() })
  expect(createWidget).toHaveBeenCalledTimes(1)
  finishDestroy()
  await waitFor(() => expect(createWidget).toHaveBeenCalledTimes(2))
})

test('리다이렉트 결제 요청이 정상 반환돼도 결제 버튼을 다시 활성화하지 않는다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  const button = await choosePayment()
  fireEvent.click(button)
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1))
  await act(async () => { await Promise.resolve() })
  expect(button).toBeDisabled()
  expect(screen.getByText('결제 진행 중입니다.')).toBeInTheDocument()
})

test('주문 요청 중 필수 약관 동의가 해제되면 결제 요청을 중단한다', async () => {
  let finishOrder!: () => void
  const pendingOrder = new Promise<Response>((resolve) => { finishOrder = () => resolve(Response.json(order)) })
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockImplementationOnce(() => pendingOrder)
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  fireEvent.click(await choosePayment())
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
  act(() => listeners.agreementStatusChange(false))
  await act(async () => finishOrder())
  expect(requestPayment).not.toHaveBeenCalled()
  const button = screen.getByRole('button', { name: '결제하기' })
  expect(button).toBeEnabled()
  fireEvent.click(button)
  expect(screen.getByText('필수 약관에 동의해 주세요.')).toBeInTheDocument()
  expect(fetcher).toHaveBeenCalledTimes(2)
  act(() => listeners.agreementStatusChange(true))
  fireEvent.click(button)
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1))
  expect(fetcher).toHaveBeenCalledTimes(2)
})

test('배송지를 바꾼 새 주문 시도에는 새 멱등성 키를 사용한다', async () => {
  const secondAddress = { ...address, shippingAddressId: 8, addressName: '회사' }
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json({ ...checkout, shippingAddresses: [address, secondAddress] })).mockRejectedValueOnce(new TypeError('network')).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  const button = await choosePayment()
  fireEvent.click(button)
  await screen.findByText('네트워크 연결을 확인하고 다시 시도해 주세요.')
  fireEvent.click(screen.getByRole('radio', { name: /회사/ }))
  fireEvent.click(button)
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1))
  const first = new Headers(fetcher.mock.calls[1][1]?.headers).get('Idempotency-Key')
  const second = new Headers(fetcher.mock.calls[2][1]?.headers).get('Idempotency-Key')
  expect(first).not.toBe(second)
})

test('확정 금액 재설정 중 주문이 만료되면 결제 요청을 하지 않는다', async () => {
  vi.spyOn(Date, 'now').mockReturnValue(1000)
  const expiringOrder = { ...order, expiresAt: new Date(2000).toISOString() }
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(expiringOrder))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  setAmount.mockImplementationOnce(async () => undefined).mockImplementationOnce(async () => { vi.spyOn(Date, 'now').mockReturnValue(3000) })
  show()
  fireEvent.click(await choosePayment())
  expect(await screen.findByText('주문 유효 시간이 지났습니다. 새 주문을 시도해 주세요.')).toBeInTheDocument()
  expect(requestPayment).not.toHaveBeenCalled()
})

test('위젯 렌더링이 실패하면 주문을 막고 다시 준비할 수 있다', async () => {
  const fetcher = vi.fn(async () => Response.json(checkout))
  vi.stubGlobal('fetch', fetcher)
  createWidget.mockResolvedValueOnce({ setAmount, renderPaymentMethods: async () => { throw new Error('SDK') }, renderAgreement: async () => ({ on: vi.fn() }), requestPayment, destroy })
  show()
  expect(await screen.findByText('결제수단을 준비하지 못했습니다. 다시 시도해 주세요.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '결제하기' })).toBeDisabled()
  expect(fetcher).toHaveBeenCalledTimes(1)
  readyWidget()
  fireEvent.click(screen.getByRole('button', { name: '결제수단 다시 시도' }))
  await waitFor(() => expect(listeners.agreementStatusChange).toBeDefined())
  expect(createWidget).toHaveBeenCalledTimes(2)
})

test('만료 주문 뒤 새 시도는 새 키로 주문을 만든다', async () => {
  const expiredOrder = { ...order, expiresAt: '2000-01-01T00:00:00Z' }
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(Response.json(expiredOrder)).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  fireEvent.click(await choosePayment())
  expect(await screen.findByText('주문 유효 시간이 지났습니다. 새 주문을 시도해 주세요.')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '새 주문 시도' }))
  fireEvent.click(screen.getByRole('button', { name: '결제하기' }))
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1))
  const first = new Headers(fetcher.mock.calls[1][1]?.headers).get('Idempotency-Key')
  const second = new Headers(fetcher.mock.calls[2][1]?.headers).get('Idempotency-Key')
  expect(first).not.toBe(second)
})

test('멱등성 충돌 뒤 다시 요청할 때 새 키로 시작한다', async () => {
  const conflict = Response.json({ code: 'ORDER_IDEMPOTENCY_CONFLICT', detail: '내부 오류' }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })
  const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(checkout)).mockResolvedValueOnce(conflict).mockResolvedValueOnce(Response.json(order))
  vi.stubGlobal('fetch', fetcher)
  readyWidget()
  show()
  const button = await choosePayment()
  fireEvent.click(button)
  expect(await screen.findByText('주문 요청이 변경됐습니다. 다시 시도해 주세요.')).toBeInTheDocument()
  fireEvent.click(button)
  await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1))
  const first = new Headers(fetcher.mock.calls[1][1]?.headers).get('Idempotency-Key')
  const second = new Headers(fetcher.mock.calls[2][1]?.headers).get('Idempotency-Key')
  expect(first).not.toBe(second)
})
