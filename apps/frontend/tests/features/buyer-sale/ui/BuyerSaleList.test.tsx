import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, test, vi } from 'vitest'

import { BuyerSaleList } from '../../../../src/features/buyer-sale/ui/BuyerSaleList.tsx'
import type { BuyerSaleProduct } from '../../../../src/features/buyer-sale/model/buyerSale.ts'

const sale: BuyerSaleProduct = {
  saleId: 11,
  productId: 21,
  name: '한정판 후디',
  description: '카드에서 숨길 설명',
  price: 120000,
  quantity: 10,
  saleDate: '2026-09-10',
  startsAt: '2026-09-09T15:00:00Z',
  endsAt: '2026-09-10T15:00:00Z',
  status: 'ON_SALE',
  representativeImagePath: '/products/21/main.webp',
}

describe('구매자 판매 상품 목록', () => {
  test('loading, 빈 결과와 오류를 서로 다른 상태로 표시한다', () => {
    const { rerender } = render(<MemoryRouter><BuyerSaleList sales={undefined} isLoading isError={false} onRetry={vi.fn()} imageBaseUrl="" /></MemoryRouter>)
    expect(screen.getByRole('status')).toHaveTextContent('판매 상품을 불러오는 중입니다.')

    rerender(<MemoryRouter><BuyerSaleList sales={[]} isLoading={false} isError={false} onRetry={vi.fn()} imageBaseUrl="" /></MemoryRouter>)
    expect(screen.getByText('선택한 날짜에 판매 상품이 없습니다.')).toBeInTheDocument()

    const retry = vi.fn()
    rerender(<MemoryRouter><BuyerSaleList sales={undefined} isLoading={false} isError onRetry={retry} imageBaseUrl="" /></MemoryRouter>)
    expect(screen.getByRole('alert')).toHaveTextContent('판매 상품을 불러오지 못했습니다.')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(retry).toHaveBeenCalledOnce()
  })

  test('상품 순서를 유지하고 카드 전체를 해당 판매 상세 링크로 표시한다', () => {
    const ended = { ...sale, saleId: 12, productId: 22, name: '두 번째 상품', status: 'ENDED' as const }
    const { container } = render(<MemoryRouter><BuyerSaleList sales={[sale, ended]} isLoading={false} isError={false} onRetry={vi.fn()} imageBaseUrl="https://images.example.com/" /></MemoryRouter>)
    const cards = screen.getAllByRole('article')
    expect(cards.map((card) => card.textContent)).toEqual([
      expect.stringContaining('한정판 후디'),
      expect.stringContaining('두 번째 상품'),
    ])
    expect(cards[0]).toHaveTextContent('LIVE')
    expect(cards[0]).toHaveTextContent('120,000원')
    expect(cards[0]).not.toHaveTextContent('카드에서 숨길 설명')
    expect(cards[0]).not.toHaveTextContent('10')
    const links = screen.getAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/sales/11', '/sales/12'])
    expect(links[0]).toHaveAccessibleName('한정판 후디 상품 상세 보기')
    expect(container.querySelector('article button')).toBeNull()
    expect(screen.getByRole('img', { name: '한정판 후디' })).toHaveAttribute('src', 'https://images.example.com/products/21/main.webp')
  })

  test('이미지 설정이 없거나 이미지 로드가 실패하면 접근 가능한 대체 영역을 표시한다', () => {
    const { rerender } = render(<MemoryRouter><BuyerSaleList sales={[sale]} isLoading={false} isError={false} onRetry={vi.fn()} imageBaseUrl="" /></MemoryRouter>)
    expect(screen.getByRole('img', { name: '한정판 후디 이미지 없음' })).toBeInTheDocument()

    rerender(<MemoryRouter><BuyerSaleList sales={[sale]} isLoading={false} isError={false} onRetry={vi.fn()} imageBaseUrl="https://images.example.com" /></MemoryRouter>)
    fireEvent.error(screen.getByRole('img', { name: '한정판 후디' }))
    expect(screen.getByRole('img', { name: '한정판 후디 이미지 없음' })).toBeInTheDocument()
  })
})
