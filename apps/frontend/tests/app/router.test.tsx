import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
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
  ['/unknown', '페이지를 찾을 수 없습니다.'],
])('기존 route %s의 화면을 유지한다', (path, heading) => {
  window.history.pushState({}, '', path)
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(<QueryClientProvider client={queryClient}><AppRouter /></QueryClientProvider>)

  expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
})

test.each([
  '/seller/products/new',
  '/seller/sales/new',
])('판매자 route %s는 로그인 확인 중 등록 기능을 시작하지 않는다', (path) => {
  window.history.pushState({}, '', path)
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(<QueryClientProvider client={queryClient}><AppRouter /></QueryClientProvider>)

  expect(screen.getByRole('alert')).toHaveTextContent('로그인 상태를 확인하는 중입니다.')
})

test('찾을 수 없는 페이지에서 메인 페이지로 이동한다', () => {
  window.history.pushState({}, '', '/unknown')
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(<QueryClientProvider client={queryClient}><AppRouter /></QueryClientProvider>)

  fireEvent.click(screen.getByRole('link', { name: '메인 페이지로 이동' }))

  expect(screen.getByRole('heading', { level: 1, name: '판매 일정' })).toBeInTheDocument()
})

test.each([
  ['/payments/toss/success?paymentKey=key&orderId=order-1&amount=120000', '결제 결과를 확인하고 있습니다.'],
  ['/payments/toss/fail?code=PAY_PROCESS_CANCELED', '결제를 취소했습니다.'],
])('%s 직접 접근을 결제 결과 페이지에 연결한다', (path, heading) => {
  window.history.pushState({}, '', path)
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })

  render(<QueryClientProvider client={queryClient}><AppRouter /></QueryClientProvider>)

  expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
})

test.each([
  ['/auth/success', '로그인 상태를 확인하고 있습니다.'],
  ['/auth/failure?error=EMAIL_UNVERIFIED', '검증된 이메일이 필요합니다.'],
])('%s 직접 접근을 로그인 결과 페이지에 연결한다', (path, heading) => {
  window.history.pushState({}, '', path)
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(<QueryClientProvider client={queryClient}><AppRouter /></QueryClientProvider>)

  expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
})
