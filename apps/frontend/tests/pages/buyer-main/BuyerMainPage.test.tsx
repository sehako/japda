import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

import App from '../../../src/App.tsx'

beforeEach(() => {
  window.history.pushState({}, '', '/')
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({ sales: [] })))
})

afterEach(() => vi.unstubAllGlobals())

test('/에서 구매자 메인과 동작 없는 로그인 버튼을 표시한다', async () => {
  render(<App />)
  expect(await screen.findByRole('heading', { name: '판매 일정' })).toBeInTheDocument()
  expect(screen.getByText('JAPDA')).toBeInTheDocument()
  const login = screen.getByRole('button', { name: '로그인' })
  fireEvent.click(login)
  expect(window.location.pathname).toBe('/')
  await waitFor(() => expect(screen.getByText('선택한 날짜에 판매 상품이 없습니다.')).toBeInTheDocument())
})
