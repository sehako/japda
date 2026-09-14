import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'

import { AuthSuccessPage } from '../../../src/pages/auth-success/AuthSuccessPage.tsx'

function Destination() {
  const location = useLocation()
  return <p data-testid="destination">{`${location.pathname}${location.search}${location.hash}`}</p>
}

afterEach(() => { window.sessionStorage.clear(); vi.unstubAllGlobals() })

test('실제 현재 사용자 응답이 도착하기 전에는 이동하지 않고 확인 후 복귀한다', async () => {
  window.sessionStorage.setItem('japda.auth.returnPath', '/checkout/11?quantity=3#order')
  let resolveResponse!: (response: Response) => void
  const response = new Promise<Response>((resolve) => { resolveResponse = resolve })
  vi.stubGlobal('fetch', vi.fn(() => response))
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/auth/success']}><Routes>
    <Route path="/auth/success" element={<AuthSuccessPage />} />
    <Route path="*" element={<Destination />} />
  </Routes></MemoryRouter></QueryClientProvider>)

  expect(screen.getByRole('status')).toHaveTextContent('로그인 상태를 확인하고 있습니다.')
  expect(screen.queryByTestId('destination')).not.toBeInTheDocument()
  expect(window.sessionStorage.getItem('japda.auth.returnPath')).toBe('/checkout/11?quantity=3#order')

  resolveResponse(Response.json({ id: 7, email: 'buyer@example.com', roles: ['BUYER'] }))

  expect(await screen.findByTestId('destination')).toHaveTextContent('/checkout/11?quantity=3#order')
  expect(window.sessionStorage.getItem('japda.auth.returnPath')).toBeNull()
})
