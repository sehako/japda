import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, test, vi } from 'vitest'

import type { BuyerOrder } from '../../../../src/features/buyer-order-history/model/buyerOrderHistory.ts'
import { BuyerOrderList } from '../../../../src/features/buyer-order-history/ui/BuyerOrderList.tsx'

const pendingOrder: BuyerOrder = {
  orderId: 1281,
  status: 'PENDING_PAYMENT',
  productName: '오브제 스틸 테이블 램프',
  quantity: 2,
  unitPrice: 129000,
  totalPrice: 258000,
  createdAt: new Date(2026, 8, 13, 20, 18).toISOString(),
  expiresAt: new Date(2026, 8, 13, 20, 21).toISOString(),
}

const paidOrder: BuyerOrder = {
  orderId: 1284,
  status: 'PAID',
  productName: 'JAPDA × ORBIT 레더 스니커즈',
  quantity: 1,
  unitPrice: 189000,
  totalPrice: 189000,
  createdAt: new Date(2026, 8, 14, 14, 32).toISOString(),
  expiresAt: new Date(2026, 8, 14, 14, 35).toISOString(),
}

const defaultProps = {
  orders: [pendingOrder, paidOrder],
  isLoading: false,
  isInitialError: false,
  hasNextPage: false,
  isFetchingNextPage: false,
  nextPageError: 'none' as const,
  onRetryInitial: vi.fn(),
  onLoadMore: vi.fn(),
  onRetryNextPage: vi.fn(),
  onRestart: vi.fn(),
}

describe('구매자 주문 목록', () => {
  test('주문별 상품, 수량, 금액, 날짜와 상태를 표시한다', () => {
    render(<MemoryRouter><BuyerOrderList {...defaultProps} /></MemoryRouter>)

    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('오브제 스틸 테이블 램프')
    expect(rows[0]).toHaveTextContent('주문 번호 #1281')
    expect(rows[0]).toHaveTextContent('수량 2개 × 129,000원')
    expect(rows[0]).toHaveTextContent('주문 금액258,000원')
    expect(rows[0]).toHaveTextContent('결제 대기')
    expect(within(rows[0]).getByText('2026. 09. 13. 20:18')).toHaveAttribute('datetime', pendingOrder.createdAt)

    expect(rows[1]).toHaveTextContent('결제 완료')
    expect(rows[1]).toHaveTextContent('189,000원')
  })

  test('결제 대기 주문에만 결제 기한을 표시한다', () => {
    render(<MemoryRouter><BuyerOrderList {...defaultProps} /></MemoryRouter>)

    const rows = screen.getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('결제 기한 2026. 09. 13. 20:21')
    expect(rows[1]).not.toHaveTextContent('결제 기한')
  })

  test('최초 로딩을 접근 가능한 진행 상태로 표시한다', () => {
    render(<MemoryRouter><BuyerOrderList {...defaultProps} orders={undefined} isLoading /></MemoryRouter>)

    expect(screen.getByRole('status')).toHaveTextContent('주문 내역을 불러오는 중입니다.')
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  test('빈 주문 내역과 상품 목록 이동 링크를 표시한다', () => {
    render(<MemoryRouter><BuyerOrderList {...defaultProps} orders={[]} /></MemoryRouter>)

    expect(screen.getByRole('heading', { name: '아직 구매 내역이 없습니다.' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '상품 보러 가기' })).toHaveAttribute('href', '/')
  })

  test('최초 조회 오류는 목록 대신 재시도 동작을 표시한다', () => {
    const onRetryInitial = vi.fn()
    render(<MemoryRouter><BuyerOrderList {...defaultProps} orders={undefined} isInitialError onRetryInitial={onRetryInitial} /></MemoryRouter>)

    expect(screen.getByRole('alert')).toHaveTextContent('주문 내역을 불러오지 못했습니다.')
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetryInitial).toHaveBeenCalledOnce()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  test('다음 페이지가 있으면 추가 조회 동작을 제공한다', () => {
    const onLoadMore = vi.fn()
    render(<MemoryRouter><BuyerOrderList {...defaultProps} hasNextPage onLoadMore={onLoadMore} /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: '주문 더 불러오기' }))
    expect(onLoadMore).toHaveBeenCalledOnce()
  })

  test('추가 조회 중에는 기존 목록을 유지하고 중복 동작을 막는다', () => {
    const onLoadMore = vi.fn()
    render(<MemoryRouter><BuyerOrderList {...defaultProps} hasNextPage isFetchingNextPage onLoadMore={onLoadMore} /></MemoryRouter>)

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    const button = screen.getByRole('button', { name: '주문을 불러오는 중입니다.' })
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(onLoadMore).not.toHaveBeenCalled()
  })

  test('추가 조회 오류는 기존 목록 아래에 별도 재시도 동작을 표시한다', () => {
    const onRetryNextPage = vi.fn()
    render(<MemoryRouter><BuyerOrderList {...defaultProps} hasNextPage nextPageError="general" onRetryNextPage={onRetryNextPage} /></MemoryRouter>)

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('추가 주문을 불러오지 못했습니다.')
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }))
    expect(onRetryNextPage).toHaveBeenCalledOnce()
  })

  test('잘못된 cursor 오류는 첫 페이지부터 다시 시작하는 동작을 표시한다', () => {
    const onRestart = vi.fn()
    render(<MemoryRouter><BuyerOrderList {...defaultProps} nextPageError="invalid-cursor" onRestart={onRestart} /></MemoryRouter>)

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('주문 내역을 처음부터 다시 불러와야 합니다.')
    fireEvent.click(within(alert).getByRole('button', { name: '처음부터 다시 불러오기' }))
    expect(onRestart).toHaveBeenCalledOnce()
  })
})
