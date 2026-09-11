import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { describe, expect, test } from 'vitest'

import { BuyerSaleDetail } from '../../../../src/features/buyer-sale/ui/BuyerSaleDetail.tsx'
import type { BuyerSaleProductDetail } from '../../../../src/features/buyer-sale/model/buyerSale.ts'

const detail: BuyerSaleProductDetail = {
  saleId: 11,
  productId: 21,
  name: '한정판 후디',
  description: '첫첫한 후디입니다.\n여러 줄 설명입니다.',
  price: 120000,
  quantity: 10,
  saleDate: '2026-09-10',
  startsAt: '2026-09-09T15:00:00Z',
  endsAt: '2026-09-10T15:00:00Z',
  status: 'ON_SALE',
  images: [
    { path: '/products/21/main.webp', displayOrder: 0, isRepresentative: true },
    { path: '/products/21/detail-1.webp', displayOrder: 1, isRepresentative: false },
    { path: '/products/21/detail-2.webp', displayOrder: 2, isRepresentative: false },
  ],
}

function renderDetail(value = detail, imageBaseUrl = 'https://images.example.com/') {
  function CurrentPath() {
    return <output aria-label="현재 경로">{useLocation().pathname}</output>
  }
  return render(<MemoryRouter initialEntries={['/sales/11']}><BuyerSaleDetail detail={value} imageBaseUrl={imageBaseUrl} /><CurrentPath /></MemoryRouter>)
}

describe('구매자 판매 상품 상세', () => {
  test('대표 이미지와 핵심 정보 다음에 설명과 추가 이미지를 순서대로 표시한다', () => {
    renderDetail()

    expect(screen.getByRole('heading', { level: 1, name: '한정판 후디' })).toBeInTheDocument()
    expect(screen.getByText('LIVE')).toBeInTheDocument()
    expect(screen.getByText('120,000원')).toBeInTheDocument()
    expect(screen.getByText('2026. 09. 10. 00:00')).toBeInTheDocument()
    expect(screen.getByText('— 2026. 09. 11. 00:00 KST')).toBeInTheDocument()
    expect(screen.queryByText('10')).not.toBeInTheDocument()
    expect(screen.getByText(/^첫첫한 후디입니다/).textContent).toBe('첫첫한 후디입니다.\n여러 줄 설명입니다.')

    expect(screen.getAllByRole('img').map((image) => image.getAttribute('alt'))).toEqual([
      '한정판 후디',
      '한정판 후디 상세 이미지 1',
      '한정판 후디 상세 이미지 2',
    ])
    expect(screen.getByRole('link', { name: '상품 목록' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('button', { name: '구매하기' })).toBeEnabled()
  })

  test('구매하기를 활성화해도 화면이나 상태를 변경하지 않는다', () => {
    renderDetail()
    fireEvent.click(screen.getByRole('button', { name: '구매하기' }))

    expect(screen.getByRole('status', { name: '현재 경로' })).toHaveTextContent('/sales/11')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '한정판 후디' })).toBeInTheDocument()
  })

  test.each(['UPCOMING', 'ENDED'] as const)('%s 상태에서 구매하기를 비활성화한다', (status) => {
    renderDetail({ ...detail, status })

    expect(screen.getByRole('button', { name: '구매하기' })).toBeDisabled()
  })

  test('설명과 추가 이미지가 모두 없으면 중립적인 안내를 표시한다', () => {
    renderDetail({ ...detail, description: null, images: [detail.images[0]] })
    expect(screen.getByText('추가 상세 정보가 없습니다.')).toBeInTheDocument()
  })

  test('기준 URL 오류와 개별 이미지 로드 실패를 해당 이미지만의 대체 영역으로 표시한다', () => {
    const { rerender } = renderDetail(detail, '')
    expect(screen.getByRole('img', { name: '한정판 후디 대표 이미지 없음' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '한정판 후디 상세 이미지 1 없음' })).toBeInTheDocument()

    rerender(<MemoryRouter><BuyerSaleDetail detail={detail} imageBaseUrl="https://images.example.com" /></MemoryRouter>)
    fireEvent.error(screen.getByRole('img', { name: '한정판 후디 상세 이미지 1' }))
    expect(screen.getByRole('img', { name: '한정판 후디 상세 이미지 1 없음' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '한정판 후디 상세 이미지 2' })).toBeInTheDocument()
  })
})
