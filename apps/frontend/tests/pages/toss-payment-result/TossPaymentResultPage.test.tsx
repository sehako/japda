import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'

import { TossPaymentResultPage } from '../../../src/pages/toss-payment-result/TossPaymentResultPage.tsx'

const successPath = '/payments/toss/success?paymentKey=secret-payment-key&orderId=order-1&amount=120000&saleId=11&quantity=3'
const paid = { orderId: 1284, paymentOrderId: 'order-1', status: 'PAID', totalAmount: 120000, approvedAt: '2026-09-14T05:32:00Z' }

function stubAuthenticatedPayment(response: () => Response) {
  const fetcher = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)
    if (path.endsWith('/api/auth/me')) return Response.json({ id: 42, email: 'buyer@example.com', roles: ['BUYER'] })
    if (path.endsWith('/api/auth/csrf')) return Response.json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
    return response()
  })
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

function renderResult(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false }, queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/payments/toss/success" element={<TossPaymentResultPage result="success" />} />
    <Route path="/payments/toss/fail" element={<TossPaymentResultPage result="fail" />} />
  </Routes></MemoryRouter></QueryClientProvider>)
}

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

test('로그인 만료 복귀에서는 승인 없이 결과 확인 필요와 중립적 링크를 표시한다', async () => {
  const fetcher = vi.fn(async () => Response.json(
    { code: 'AUTH_UNAUTHENTICATED', detail: '로그인이 필요합니다.' },
    { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
  ))
  vi.stubGlobal('fetch', fetcher)
  renderResult(successPath)

  expect(await screen.findByRole('heading', { name: '결제 결과를 아직 확인할 수 없습니다.' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '상품 목록으로 돌아가기' })).toHaveAttribute('href', '/')
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(screen.queryByRole('link', { name: '결제 화면으로 돌아가기' })).not.toBeInTheDocument()
})

test('검증된 승인 응답에만 서버 주문 정보와 결제 완료를 표시한다', async () => {
  stubAuthenticatedPayment(() => Response.json(paid))
  renderResult(successPath)

  expect(screen.getByRole('status')).toHaveTextContent('결제 승인 결과를 확인하는 중입니다.')
  expect(screen.queryByText('결제가 완료됐습니다.')).not.toBeInTheDocument()
  expect(await screen.findByRole('heading', { name: '결제가 완료됐습니다.' })).toBeInTheDocument()
  expect(screen.getByText('1284')).toBeInTheDocument()
  expect(screen.getByText('120,000원')).toBeInTheDocument()
  expect(screen.getByText('2026. 09. 14. 14:32')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '상품 목록으로 돌아가기' })).toHaveAttribute('href', '/')
  expect(document.body).not.toHaveTextContent('secret-payment-key')
})

test.each([
  '/payments/toss/success?orderId=order-1&amount=120000',
  '/payments/toss/success?paymentKey=key&orderId=order-1&amount=0',
  '/payments/toss/success?paymentKey=key&orderId=order-1&amount=abc',
  '/payments/toss/success?paymentKey=key&paymentKey=other&orderId=order-1&amount=120000',
  '/payments/toss/success?paymentKey=key&orderId=&amount=120000',
])('잘못된 성공 리다이렉트 %s에서는 승인을 요청하지 않는다', (path) => {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  renderResult(path)

  expect(screen.getByRole('alert')).toHaveTextContent('결제 정보를 확인할 수 없습니다.')
  expect(screen.getByRole('alert')).toHaveTextContent('요청 정보가 올바르지 않아 승인을 진행할 수 없습니다.')
  expect(fetcher).not.toHaveBeenCalled()
  expect(screen.getByRole('link', { name: '상품 목록으로 돌아가기' })).toHaveAttribute('href', '/')
})

test('미리보기 매개변수는 승인 결과를 바꾸지 않는다', async () => {
  stubAuthenticatedPayment(() => Response.json(paid))
  renderResult(`${successPath}&state=failed&reason=expired&return=invalid`)

  expect(await screen.findByRole('heading', { name: '결제가 완료됐습니다.' })).toBeInTheDocument()
  expect(screen.queryByText('주문이 만료됐습니다.')).not.toBeInTheDocument()
})

test('주문 식별자가 다른 승인 응답은 완료로 표시하지 않는다', async () => {
  stubAuthenticatedPayment(() => Response.json({ ...paid, paymentOrderId: 'another-order' }))
  renderResult(successPath)

  expect(await screen.findByRole('heading', { name: '결제 결과를 아직 확인할 수 없습니다.' })).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('결제가 완료됐을 수 있으니 잠시만 기다려 주세요.')
  expect(screen.queryByText('1284')).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '결제 화면으로 돌아가기' })).not.toBeInTheDocument()
})

test('확정 실패는 검증된 체크아웃 복귀 링크를 제공한다', async () => {
  stubAuthenticatedPayment(() => Response.json({ code: 'PAYMENT_CONFIRMATION_FAILED' }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } }))
  renderResult(successPath)

  expect(await screen.findByRole('heading', { name: '결제에 실패했습니다.' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '결제 화면으로 돌아가기' })).toHaveAttribute('href', '/checkout/11?quantity=3')
})

test('결제창 취소 코드를 일반 실패와 구분하고 외부 메시지를 숨긴다', () => {
  renderResult('/payments/toss/fail?code=PAY_PROCESS_CANCELED&message=외부오류&saleId=11&quantity=3')

  expect(screen.getByRole('alert')).toHaveTextContent('결제를 취소했습니다.')
  expect(screen.getByRole('link', { name: '결제 화면으로 돌아가기' })).toHaveAttribute('href', '/checkout/11?quantity=3')
  expect(document.body).not.toHaveTextContent('외부오류')
})

test('일반 인증 실패에서도 외부 메시지를 숨긴다', () => {
  renderResult('/payments/toss/fail?code=UNKNOWN&message=신뢰할수없는문구&saleId=11&quantity=3')

  expect(screen.getByRole('alert')).toHaveTextContent('결제 인증에 실패했습니다.')
  expect(document.body).not.toHaveTextContent('신뢰할수없는문구')
})

test.each([
  '/payments/toss/fail?code=UNKNOWN&saleId=0&quantity=3',
  '/payments/toss/fail?code=UNKNOWN&saleId=11&quantity=2147483648',
  '/payments/toss/fail?code=UNKNOWN&saleId=11&quantity=3&quantity=4',
])('잘못된 복귀 매개변수 %s를 체크아웃 링크에 반영하지 않는다', (path) => {
  renderResult(path)

  expect(screen.queryByRole('link', { name: '결제 화면으로 돌아가기' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '상품 목록으로 돌아가기' })).toHaveAttribute('href', '/')
})
