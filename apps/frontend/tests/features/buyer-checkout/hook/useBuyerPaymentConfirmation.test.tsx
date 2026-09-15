import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import { useBuyerPaymentConfirmation } from '../../../../src/features/buyer-checkout/hook/useBuyerPaymentConfirmation.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'
import type { TossSuccessParameters } from '../../../../src/features/buyer-checkout/model/tossPaymentResult.ts'

const { confirm, currentUser, prepareCsrfToken } = vi.hoisted(() => ({ confirm: vi.fn(), currentUser: vi.fn(), prepareCsrfToken: vi.fn() }))
vi.mock('../../../../src/features/buyer-checkout/api/buyerPaymentConfirmationApi.ts', () => ({ confirmBuyerPayment: confirm }))
vi.mock('../../../../src/features/authentication/hook/useCurrentUser.ts', () => ({ useCurrentUser: currentUser }))
vi.mock('../../../../src/shared/api/apiClient.ts', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../../../src/shared/api/apiClient.ts')>(), prepareCsrfToken,
}))

const params: TossSuccessParameters = { paymentKey: 'payment-key', orderId: 'order_123456', amount: 70000 }
const paid = { orderId: 51, paymentOrderId: params.orderId, status: 'PAID', totalAmount: 70000, approvedAt: '2026-09-14T05:00:00Z' }

let queryClient: QueryClient

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  confirm.mockReset()
  currentUser.mockReset().mockReturnValue({ status: 'authenticated' })
  prepareCsrfToken.mockReset().mockResolvedValue(undefined)
  queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
})

afterEach(() => vi.useRealTimers())

test('유효한 성공 정보로 한 번 승인하고 검증된 주문을 반환한다', async () => {
  confirm.mockResolvedValue(paid)
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })

  expect(result.current.kind).toBe('confirming')
  await waitFor(() => expect(result.current).toEqual({ kind: 'paid', payment: paid }))
  expect(prepareCsrfToken).toHaveBeenCalledWith({ baseUrl: '' })
  expect(confirm).toHaveBeenCalledExactlyOnceWith(params, { baseUrl: '' })
})

test('성공 정보가 없으면 로그인 확인과 승인 요청을 시작하지 않는다', async () => {
  const { result } = renderHook(() => useBuyerPaymentConfirmation(null), { wrapper })
  expect(result.current.kind).toBe('invalid')
  await act(async () => { await Promise.resolve() })
  expect(currentUser).toHaveBeenCalledWith(false)
  expect(prepareCsrfToken).not.toHaveBeenCalled()
  expect(confirm).not.toHaveBeenCalled()
})

test('로그인 확인 중에는 승인 요청을 시작하지 않고 확인 완료 후에만 진행한다', async () => {
  currentUser.mockReturnValue({ status: 'checking' })
  confirm.mockResolvedValue(paid)
  const { result, rerender } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await act(async () => { await Promise.resolve() })
  expect(result.current.kind).toBe('confirming')
  expect(confirm).not.toHaveBeenCalled()
  currentUser.mockReturnValue({ status: 'authenticated' })
  rerender()
  await waitFor(() => expect(result.current.kind).toBe('paid'))
  expect(confirm).toHaveBeenCalledTimes(1)
})

test.each(['unauthenticated', 'error'] as const)('%s 상태에서는 승인하지 않고 결과 확인 필요로 전환한다', async (status) => {
  currentUser.mockReturnValue({ status })
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current.kind).toBe('review'))
  expect(prepareCsrfToken).not.toHaveBeenCalled()
  expect(confirm).not.toHaveBeenCalled()
})

test('CSRF 준비 실패 시 승인 요청을 보내지 않고 결과 확인 필요로 전환한다', async () => {
  prepareCsrfToken.mockRejectedValue(new ApiError('토큰 조회 실패', { status: 503 }))
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current.kind).toBe('review'))
  expect(confirm).not.toHaveBeenCalled()
})

test.each([
  [401, 'AUTH_UNAUTHENTICATED'],
  [403, 'AUTH_BUYER_LINK_REQUIRED'],
  [403, 'AUTH_CSRF_INVALID'],
] as const)('승인 중 %s %s 오류는 결과 확인 필요로 전환하고 자동 재시도하지 않는다', async (status, code) => {
  confirm.mockRejectedValue(new ApiError('승인 중 오류', { status, code }))
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current.kind).toBe('review'))
  await act(async () => { await vi.advanceTimersByTimeAsync(75_000) })
  expect(confirm).toHaveBeenCalledTimes(1)
})

test('StrictMode 재실행과 다시 렌더링에도 최초 승인 요청은 한 번만 보낸다', async () => {
  confirm.mockImplementation(() => new Promise(() => undefined))
  const Wrapper = ({ children }: { children: ReactNode }) => <StrictMode>{wrapper({ children })}</StrictMode>
  const { rerender } = renderHook(() => useBuyerPaymentConfirmation({ ...params }), { wrapper: Wrapper })
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
  rerender()
  await act(async () => { await Promise.resolve() })
  expect(confirm).toHaveBeenCalledTimes(1)
})

test('처리 중 응답 뒤 15초마다 최대 5회 추가 확인하고 시간 초과로 전환한다', async () => {
  confirm.mockRejectedValue(new ApiError('처리 중', { status: 409, code: 'PAYMENT_CONFIRMATION_IN_PROGRESS' }))
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current.kind).toBe('processing'))
  expect(confirm).toHaveBeenCalledTimes(1)

  for (let attempt = 2; attempt <= 6; attempt += 1) {
    await act(async () => { await vi.advanceTimersByTimeAsync(14_000) })
    expect(confirm).toHaveBeenCalledTimes(attempt - 1)
    await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
    await waitFor(() => expect(confirm).toHaveBeenCalledTimes(attempt))
  }
  await waitFor(() => expect(result.current).toEqual({ kind: 'review', reason: 'timeout' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(15_000) })
  expect(confirm).toHaveBeenCalledTimes(6)
})

test('추가 확인이 끝나기 전에는 다음 요청을 시작하지 않는다', async () => {
  let finish!: (value: typeof paid) => void
  confirm.mockRejectedValueOnce(new ApiError('처리 중', { status: 409, code: 'PAYMENT_CONFIRMATION_IN_PROGRESS' }))
    .mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current.kind).toBe('processing'))
  await act(async () => { await vi.advanceTimersByTimeAsync(75_000) })
  expect(confirm).toHaveBeenCalledTimes(2)
  await act(async () => { finish(paid) })
  await waitFor(() => expect(result.current.kind).toBe('paid'))
})

test('추가 확인에서 결제 완료가 오면 남은 확인을 멈춘다', async () => {
  confirm.mockRejectedValueOnce(new ApiError('처리 중', { status: 409, code: 'PAYMENT_CONFIRMATION_IN_PROGRESS' }))
    .mockResolvedValueOnce(paid)
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current.kind).toBe('processing'))
  await act(async () => { await vi.advanceTimersByTimeAsync(15_000) })
  await waitFor(() => expect(result.current.kind).toBe('paid'))
  await act(async () => { await vi.advanceTimersByTimeAsync(75_000) })
  expect(confirm).toHaveBeenCalledTimes(2)
})

test('추가 확인에서 다른 오류가 오면 남은 확인을 멈춘다', async () => {
  confirm.mockRejectedValueOnce(new ApiError('처리 중', { status: 409, code: 'PAYMENT_CONFIRMATION_IN_PROGRESS' }))
    .mockRejectedValueOnce(new ApiError('확정 실패', { status: 409, code: 'PAYMENT_CONFIRMATION_FAILED' }))
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current.kind).toBe('processing'))
  await act(async () => { await vi.advanceTimersByTimeAsync(15_000) })
  await waitFor(() => expect(result.current).toEqual({ kind: 'failed', reason: 'rejected' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(75_000) })
  expect(confirm).toHaveBeenCalledTimes(2)
})

test('화면을 떠나면 예정된 추가 확인을 실행하지 않는다', async () => {
  confirm.mockRejectedValue(new ApiError('처리 중', { status: 409, code: 'PAYMENT_CONFIRMATION_IN_PROGRESS' }))
  const { result, unmount } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current.kind).toBe('processing'))
  unmount()
  await act(async () => { await vi.advanceTimersByTimeAsync(75_000) })
  expect(confirm).toHaveBeenCalledTimes(1)
})

test('URL 결제가 바뀌면 이전 응답을 무시하고 새 결제만 표시한다', async () => {
  let finishOld!: (value: typeof paid) => void
  confirm.mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve }))
    .mockResolvedValueOnce({ ...paid, paymentOrderId: 'order_987654', orderId: 52 })
  const { result, rerender } = renderHook(({ payment }) => useBuyerPaymentConfirmation(payment), {
    initialProps: { payment: params }, wrapper,
  })
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(1))
  rerender({ payment: { ...params, orderId: 'order_987654' } })
  await waitFor(() => expect(result.current).toEqual({ kind: 'paid', payment: { ...paid, paymentOrderId: 'order_987654', orderId: 52 } }))
  await act(async () => { finishOld(paid) })
  expect(result.current.payment?.orderId).toBe(52)
})

test.each([
  ['PAYMENT_CONFIRMATION_FAILED', 'failed', 'rejected'],
  ['PAYMENT_ORDER_EXPIRED', 'expired', undefined],
  ['PAYMENT_CONFIRMATION_UNAVAILABLE', 'review', 'unavailable'],
  ['PAYMENT_REVIEW_REQUIRED', 'review', 'manual'],
  ['PAYMENT_ORDER_NOT_FOUND', 'invalid', 'rejected'],
  ['PAYMENT_AMOUNT_MISMATCH', 'invalid', 'rejected'],
  ['PAYMENT_KEY_CONFLICT', 'invalid', 'rejected'],
] as const)('%s 응답을 %s 상태로 분류한다', async (code, kind, reason) => {
  confirm.mockRejectedValue(new ApiError('외부 원문', { status: 409, code }))
  const { result } = renderHook(() => useBuyerPaymentConfirmation(params), { wrapper })
  await waitFor(() => expect(result.current).toEqual(reason ? { kind, reason } : { kind }))
})

test('계약 오류와 네트워크 오류는 결과 확인 필요로 처리한다', async () => {
  confirm.mockRejectedValueOnce(new ApiError('계약 오류')).mockRejectedValueOnce(new ApiError('네트워크 오류', {}, true))
  const { result, rerender } = renderHook(({ payment }) => useBuyerPaymentConfirmation(payment), { initialProps: { payment: params }, wrapper })
  await waitFor(() => expect(result.current).toEqual({ kind: 'review', reason: 'unavailable' }))
  rerender({ payment: { ...params, orderId: 'order_987654' } })
  await waitFor(() => expect(confirm).toHaveBeenCalledTimes(2))
  await waitFor(() => expect(result.current).toEqual({ kind: 'review', reason: 'unavailable' }))
})
