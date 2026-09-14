import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'

import { BuyerSaleDetailPage } from '../../../src/pages/buyer-sale-detail/BuyerSaleDetailPage.tsx'

const detail = {
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
} as const

function renderPage(path: string) {
  const domainFetcher = globalThis.fetch
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) =>
    String(input).endsWith('/api/auth/me')
      ? Promise.resolve(Response.json({ code: 'AUTH_UNAUTHENTICATED' }, { status: 401, headers: { 'Content-Type': 'application/problem+json' } }))
      : domainFetcher(input, init))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/sales/:saleId" element={<BuyerSaleDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

test('잘못된 saleId 경로는 API를 호출하지 않고 안내한다', () => {
  const fetcher = vi.fn<typeof fetch>()
  vi.stubGlobal('fetch', fetcher)
  renderPage('/sales/not-a-number')

  expect(screen.getByRole('alert')).toHaveTextContent('유효하지 않은 상품 경로입니다.')
  expect(screen.getByRole('link', { name: '상품 목록' })).toHaveAttribute('href', '/')
  expect(fetcher).not.toHaveBeenCalled()
})

test('상세 route에 직접 접근하면 loading 후 상품을 표시한다', async () => {
  let resolveResponse: ((response: Response) => void) | undefined
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { resolveResponse = resolve })))
  renderPage('/sales/11')

  expect(screen.getByRole('status')).toHaveTextContent('판매 상품 상세를 불러오는 중입니다.')
  resolveResponse?.(Response.json(detail))
  expect(await screen.findByRole('heading', { level: 1, name: '한정판 후디' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'JAPDA 홈' })).toHaveAttribute('href', '/')
  expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
})

test('404 SALE_NOT_FOUND를 상품 미존재로 안내한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json(
    { code: 'SALE_NOT_FOUND', detail: '내부 상세' },
    { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
  )))
  renderPage('/sales/11')

  expect(await screen.findByRole('alert')).toHaveTextContent('판매 상품을 찾을 수 없습니다.')
  expect(screen.queryByText('내부 상세')).not.toBeInTheDocument()
})

test('일반 오류에서 명시적으로 재시도해 상세를 표시한다', async () => {
  const fetcher = vi.fn()
    .mockResolvedValueOnce(new Response(null, { status: 500 }))
    .mockResolvedValueOnce(Response.json(detail))
  vi.stubGlobal('fetch', fetcher)
  renderPage('/sales/11')

  expect(await screen.findByRole('alert')).toHaveTextContent('판매 상품 상세를 불러오지 못했습니다.')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
  expect(await screen.findByRole('heading', { level: 1, name: '한정판 후디' })).toBeInTheDocument()
})
