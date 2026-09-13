import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'

import { AppRouter } from '../../src/app/router.tsx'

afterEach(() => vi.unstubAllGlobals())

test('/sales/:saleId 직접 접근을 상품 상세 페이지에 연결한다', async () => {
  window.history.pushState({}, '', '/sales/11')
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({
    saleId: 11,
    productId: 21,
    name: '한정판 후디',
    description: null,
    price: 120000,
    quantity: 10,
    saleDate: '2026-09-10',
    startsAt: '2026-09-09T15:00:00Z',
    endsAt: '2026-09-10T15:00:00Z',
    status: 'ENDED',
    images: [{ path: '/products/21/main.webp', displayOrder: 0, isRepresentative: true }],
  })))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(<QueryClientProvider client={queryClient}><AppRouter /></QueryClientProvider>)

  expect(await screen.findByRole('heading', { level: 1, name: '한정판 후디' })).toBeInTheDocument()
})

test.each([
  ['/seller/products/new', '상품 등록'],
  ['/seller/sales/new', '판매 일정 등록'],
  ['/unknown', '페이지를 찾을 수 없습니다.'],
])('기존 route %s의 화면을 유지한다', (path, heading) => {
  window.history.pushState({}, '', path)
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(<QueryClientProvider client={queryClient}><AppRouter /></QueryClientProvider>)

  expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
})

test.each([
  ['/payments/toss/success?paymentKey=key&orderId=order-1&amount=120000', '결제 인증 경로로 돌아왔지만 결제가 완료되지 않았습니다.'],
  ['/payments/toss/fail?code=PAY_PROCESS_CANCELED', '결제를 취소했습니다.'],
])('%s 직접 접근을 인증 결과 페이지에 연결한다', (path, message) => {
  window.history.pushState({}, '', path)

  render(<AppRouter />)

  expect(screen.getByRole('alert')).toHaveTextContent(message)
})
