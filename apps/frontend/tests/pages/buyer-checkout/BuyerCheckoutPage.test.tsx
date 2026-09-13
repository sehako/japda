import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, expect, test, vi } from 'vitest'

import { BuyerCheckoutPage } from '../../../src/pages/buyer-checkout/BuyerCheckoutPage.tsx'

const address = { shippingAddressId: 7, addressName: '집', recipientName: '홍길동', phoneNumber: '010', postalCode: '06236', address: '서울', detailAddress: '101호', deliveryMessage: '문 앞' }
const checkout = { saleId: 11, productName: '한정판 후디', representativeImagePath: '/products/main.webp', quantity: 3, unitPrice: 120000, totalPrice: 360000, shippingAddresses: [address, { ...address, shippingAddressId: 8, addressName: '회사' }] }

function renderPage(path: string, buyerId: number | null = 42) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[path]}><Routes><Route path="/checkout/:saleId" element={<BuyerCheckoutPage buyerId={buyerId} />} /></Routes></MemoryRouter></QueryClientProvider>)
}

afterEach(() => vi.unstubAllGlobals())

test.each(['/checkout/nope?quantity=3', '/checkout/11', '/checkout/11?quantity=0', '/checkout/11?quantity=2147483648'])('잘못된 경로 %s에서 조회를 막는다', (path) => {
  const fetcher = vi.fn<typeof fetch>()
  vi.stubGlobal('fetch', fetcher)
  renderPage(path)
  expect(screen.getByRole('alert')).toHaveTextContent('유효하지 않은 체크아웃 경로입니다.')
  expect(screen.getByRole('link', { name: '상품 목록' })).toHaveAttribute('href', '/')
  expect(fetcher).not.toHaveBeenCalled()
})

test('구매자 설정 오류에서 API를 호출하지 않는다', () => {
  const fetcher = vi.fn<typeof fetch>()
  vi.stubGlobal('fetch', fetcher)
  renderPage('/checkout/11?quantity=3', null)
  expect(screen.getByRole('alert')).toHaveTextContent('개발용 구매자 식별자 설정을 확인해 주세요.')
  expect(fetcher).not.toHaveBeenCalled()
})

test('서버 예상 총액을 표시하고 배송지를 명시적으로 선택한다', async () => {
  const fetcher = vi.fn(async () => Response.json(checkout))
  vi.stubGlobal('fetch', fetcher)
  renderPage('/checkout/11?quantity=3')
  expect(screen.getByRole('status')).toHaveTextContent('체크아웃 정보를 불러오는 중입니다.')
  expect(await screen.findByRole('heading', { level: 3, name: '한정판 후디' })).toBeInTheDocument()
  expect(screen.getByText('360,000원', { selector: 'strong' })).toBeInTheDocument()
  expect(screen.getByText('3개', { selector: 'strong' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '결제하기' })).toBeDisabled()
  expect(screen.getByRole('img', { name: '한정판 후디 대표 이미지 없음' })).toBeInTheDocument()
  expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
  const radios = screen.getAllByRole('radio')
  expect(radios[0]).not.toBeChecked()
  expect(radios[1]).not.toBeChecked()
  fireEvent.click(radios[1])
  expect(radios[1]).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '결제하기' }))
  expect(fetcher).toHaveBeenCalledTimes(1)
})

test('빈 배송지에서 등록 양식을 열고 취소한다', async () => {
  const fetcher = vi.fn(async () => Response.json({ ...checkout, shippingAddresses: [] }))
  vi.stubGlobal('fetch', fetcher)
  renderPage('/checkout/11?quantity=3')
  expect(await screen.findByText('등록된 배송지가 없습니다.')).toBeInTheDocument()
  expect(screen.queryByRole('textbox', { name: '배송지명' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '배송지 등록' }))
  expect(screen.getByRole('textbox', { name: '배송지명' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '등록 취소' }))
  expect(screen.queryByRole('textbox', { name: '배송지명' })).not.toBeInTheDocument()
  expect(fetcher).toHaveBeenCalledTimes(1)
})

test('판매 상품 없음과 일반 조회 오류를 구분하고 재시도한다', async () => {
  const fetcher = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json({ code: 'ORDER_SALE_NOT_FOUND', detail: '내부 정보' }, { status: 404, headers: { 'Content-Type': 'application/problem+json' } }))
  vi.stubGlobal('fetch', fetcher)
  const first = renderPage('/checkout/11?quantity=3')
  expect(await screen.findByRole('alert')).toHaveTextContent('판매 상품을 찾을 수 없습니다.')
  expect(screen.queryByText('내부 정보')).not.toBeInTheDocument()
  first.unmount()
  fetcher.mockReset().mockResolvedValueOnce(new Response(null, { status: 500 })).mockResolvedValueOnce(Response.json(checkout))
  renderPage('/checkout/11?quantity=3')
  expect(await screen.findByRole('alert')).toHaveTextContent('체크아웃 정보를 불러오지 못했습니다.')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByRole('heading', { level: 3, name: '한정판 후디' })).toBeInTheDocument()
})

test('등록 필수값 오류는 POST 없이 필드에 안내한다', async () => {
  const fetcher = vi.fn(async () => Response.json({ ...checkout, shippingAddresses: [] }))
  vi.stubGlobal('fetch', fetcher)
  renderPage('/checkout/11?quantity=3')
  fireEvent.click(await screen.findByRole('button', { name: '배송지 등록' }))
  fireEvent.click(screen.getByRole('button', { name: '등록하기' }))
  expect(screen.getByRole('textbox', { name: '배송지명' })).toHaveAttribute('aria-invalid', 'true')
  expect(fetcher).toHaveBeenCalledTimes(1)
})

test('배송지명 중복 오류는 입력값을 유지하고 목록 재시도 경로를 제공한다', async () => {
  const fetcher = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json({ ...checkout, shippingAddresses: [] }))
    .mockResolvedValueOnce(Response.json({ code: 'BUYER_SHIPPING_ADDRESS_NAME_DUPLICATED', detail: '내부 정보' }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } }))
    .mockResolvedValueOnce(Response.json(checkout))
  vi.stubGlobal('fetch', fetcher)
  renderPage('/checkout/11?quantity=3')
  fireEvent.click(await screen.findByRole('button', { name: '배송지 등록' }))
  for (const [label, value] of [['배송지명', '집'], ['수취인명', '홍길동'], ['전화번호', '010'], ['우편번호', '06236'], ['기본 주소', '서울'], ['상세 주소', '101호']]) {
    fireEvent.change(screen.getByRole('textbox', { name: label }), { target: { value } })
  }
  fireEvent.click(screen.getByRole('button', { name: '등록하기' }))
  expect(await screen.findByRole('alert', { name: '' })).toHaveTextContent('이미 등록된 배송지명입니다.')
  expect(screen.getByRole('textbox', { name: '배송지명' })).toHaveValue('집')
  expect(screen.queryByText('내부 정보')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '목록 다시 시도' }))
  expect(await screen.findAllByRole('radio')).toHaveLength(2)
  expect(fetcher).toHaveBeenCalledTimes(3)
})

test('등록 성공 후 목록을 재조회하고 새 배송지를 선택한다', async () => {
  const fetcher = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json({ ...checkout, shippingAddresses: [] }))
    .mockResolvedValueOnce(Response.json({ ...address, shippingAddressId: 9, createdAt: '2026-09-13T00:00:00Z' }, { status: 201 }))
    .mockResolvedValueOnce(Response.json({ ...checkout, shippingAddresses: [{ ...address, shippingAddressId: 9 }] }))
  vi.stubGlobal('fetch', fetcher)
  renderPage('/checkout/11?quantity=3')
  fireEvent.click(await screen.findByRole('button', { name: '배송지 등록' }))
  for (const [label, value] of [['배송지명', '집'], ['수취인명', '홍길동'], ['전화번호', '010'], ['우편번호', '06236'], ['기본 주소', '서울'], ['상세 주소', '101호']]) {
    fireEvent.change(screen.getByRole('textbox', { name: label }), { target: { value } })
  }
  fireEvent.click(screen.getByRole('button', { name: '등록하기' }))
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3))
  expect(await screen.findByRole('radio')).toBeChecked()
  expect(screen.queryByRole('textbox', { name: '배송지명' })).not.toBeInTheDocument()
})

test('등록 후 재조회 실패는 등록 완료를 알리고 목록만 재시도한다', async () => {
  const fetcher = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json({ ...checkout, shippingAddresses: [] }))
    .mockResolvedValueOnce(Response.json({ ...address, shippingAddressId: 9 }, { status: 201 }))
    .mockResolvedValueOnce(new Response(null, { status: 500 }))
    .mockResolvedValueOnce(Response.json({ ...checkout, shippingAddresses: [{ ...address, shippingAddressId: 9 }] }))
  vi.stubGlobal('fetch', fetcher)
  renderPage('/checkout/11?quantity=3')
  fireEvent.click(await screen.findByRole('button', { name: '배송지 등록' }))
  for (const [label, value] of [['배송지명', '집'], ['수취인명', '홍길동'], ['전화번호', '010'], ['우편번호', '06236'], ['기본 주소', '서울'], ['상세 주소', '101호']]) {
    fireEvent.change(screen.getByRole('textbox', { name: label }), { target: { value } })
  }
  fireEvent.click(screen.getByRole('button', { name: '등록하기' }))
  expect(await screen.findByText('배송지 등록은 완료됐지만 목록을 불러오지 못했습니다.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '등록하기' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '목록 다시 시도' }))
  expect(await screen.findByRole('radio')).toBeChecked()
  expect(fetcher).toHaveBeenCalledTimes(4)
})
