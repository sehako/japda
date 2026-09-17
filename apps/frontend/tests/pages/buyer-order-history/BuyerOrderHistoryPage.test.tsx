import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'

import { BuyerOrderHistoryPage } from '../../../src/pages/buyer-order-history/BuyerOrderHistoryPage.tsx'

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter><BuyerOrderHistoryPage /></MemoryRouter></QueryClientProvider>)
}

function problem(status: number, code: string) {
  return Response.json({ status, code, detail: '내부 오류 정보' }, {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

afterEach(() => vi.unstubAllGlobals())

test('로그인 상태 확인 중에는 주문 API를 호출하지 않는다', () => {
  const fetcher = vi.fn(() => new Promise<Response>(() => undefined))
  vi.stubGlobal('fetch', fetcher)

  renderPage()

  expect(screen.getByRole('heading', { level: 1, name: 'Orders' })).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('로그인 상태를 확인하고 있습니다.')
  expect(fetcher).toHaveBeenCalledOnce()
  expect(String(fetcher.mock.calls[0]?.[0])).toBe('/api/auth/me')
})

test('비로그인 사용자에게 로그인 진입 동작을 제공한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => problem(401, 'AUTH_UNAUTHENTICATED')))

  renderPage()

  expect(await screen.findByRole('alert')).toHaveTextContent('로그인이 필요합니다.')
  expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
  expect(screen.queryByText('내부 오류 정보')).not.toBeInTheDocument()
})

test('로그인 상태 확인 실패를 주문 조회 실패와 구분하고 재시도한다', async () => {
  let authenticationAttempts = 0
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).endsWith('/api/auth/me')) {
      authenticationAttempts += 1
      return authenticationAttempts === 1
        ? problem(500, 'COMMON_INTERNAL_SERVER_ERROR')
        : Response.json({ id: 7, email: 'buyer@japda.kr', roles: ['BUYER'] })
    }
    return Response.json({ items: [], nextCursor: null })
  }))

  renderPage()

  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('로그인 상태를 확인하지 못했습니다.')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('heading', { name: '아직 구매 내역이 없습니다.' })).toBeInTheDocument()
})

test('인증된 사용자의 주문 내역과 현재 내비게이션을 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/api/auth/me')
    ? Response.json({ id: 7, email: 'buyer@japda.kr', roles: ['BUYER'] })
    : Response.json({ items: [{
      orderId: 1000,
      status: 'PAID',
      productName: '한정판 상품',
      quantity: 1,
      unitPrice: 35000,
      totalPrice: 35000,
      createdAt: '2026-09-11T06:00:00Z',
      expiresAt: '2026-09-11T06:03:00Z',
    }], nextCursor: null })))

  renderPage()

  expect(await screen.findByText('한정판 상품')).toBeInTheDocument()
  expect(screen.getByText('buyer@japda.kr')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'SHOP' })).toHaveAttribute('href', '/')
  expect(screen.getByRole('link', { name: 'ORDERS' })).toHaveAttribute('aria-current', 'page')
})

test.each([
  [403, 'AUTH_BUYER_LINK_REQUIRED', '구매자 정보 연결이 필요합니다.'],
  [401, 'AUTH_UNAUTHENTICATED', '로그인이 필요합니다.'],
] as const)('주문 API의 %s %s 응답을 별도 안내로 표시한다', async (status, code, message) => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/api/auth/me')
    ? Response.json({ id: 7, email: 'buyer@japda.kr', roles: ['BUYER'] })
    : problem(status, code)))

  renderPage()

  expect(await screen.findByRole('alert')).toHaveTextContent(message)
  expect(screen.queryByText('내부 오류 정보')).not.toBeInTheDocument()
})

test('잘못된 cursor 오류에서 누적 목록을 유지하고 처음부터 재시작한다', async () => {
  let firstPageAttempts = 0
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/api/auth/me')) return Response.json({ id: 7, email: 'buyer@japda.kr', roles: ['BUYER'] })
    if (url.includes('cursor=')) return problem(400, 'ORDER_CURSOR_INVALID')
    if (!url.includes('/api/orders?')) throw new Error(`예상하지 못한 요청: ${url}`)
    firstPageAttempts += 1
    return Response.json({ items: [{
      orderId: 1000,
      status: 'PAID',
      productName: '한정판 상품',
      quantity: 1,
      unitPrice: 35000,
      totalPrice: 35000,
      createdAt: '2026-09-11T06:00:00Z',
      expiresAt: '2026-09-11T06:03:00Z',
    }], nextCursor: 'invalid-cursor' })
  }))

  renderPage()
  await screen.findByText('한정판 상품')
  fireEvent.click(await screen.findByRole('button', { name: '주문 더 불러오기' }))
  expect(firstPageAttempts).toBe(1)

  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('주문 내역을 처음부터 다시 불러와야 합니다.')
  expect(screen.getByText('한정판 상품')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '처음부터 다시 불러오기' }))

  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(firstPageAttempts).toBe(2)
})
