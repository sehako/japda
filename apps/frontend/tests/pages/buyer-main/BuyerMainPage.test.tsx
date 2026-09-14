import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import App from '../../../src/App.tsx'

beforeEach(() => {
  window.history.pushState({}, '', '/')
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
    String(input).endsWith('/api/auth/me')
      ? Response.json({ code: 'AUTH_UNAUTHENTICATED' }, { status: 401, headers: { 'Content-Type': 'application/problem+json' } })
      : Response.json({ sales: [] })))
})

afterEach(() => vi.unstubAllGlobals())

test('/에서 구매자 메인과 로그인 상태를 표시한다', async () => {
  render(<App />)
  expect(await screen.findByRole('heading', { name: '판매 일정' })).toBeInTheDocument()
  expect(screen.getByText('JAPDA')).toBeInTheDocument()
  expect(await screen.findByRole('button', { name: '로그인' })).toBeInTheDocument()
  await waitFor(() => expect(screen.getByText('선택한 날짜에 판매 상품이 없습니다.')).toBeInTheDocument())
})
