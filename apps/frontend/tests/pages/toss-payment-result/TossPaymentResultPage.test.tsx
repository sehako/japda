import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { expect, test } from 'vitest'

import { TossPaymentResultPage } from '../../../src/pages/toss-payment-result/TossPaymentResultPage.tsx'

function renderResult(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/payments/toss/success" element={<TossPaymentResultPage result="success" />} />
    <Route path="/payments/toss/fail" element={<TossPaymentResultPage result="fail" />} />
  </Routes></MemoryRouter>)
}

test('인증 성공 리다이렉트에서 결제 미완료를 알리고 복귀 링크에 검증된 값만 남긴다', () => {
  renderResult('/payments/toss/success?paymentKey=secret-payment-key&orderId=order-1&amount=120000&saleId=11&quantity=3')

  expect(screen.getByRole('alert')).toHaveTextContent('결제 인증 경로로 돌아왔지만 결제가 완료되지 않았습니다.')
  expect(screen.queryByText('결제 완료')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '체크아웃으로 돌아가기' })).toHaveAttribute('href', '/checkout/11?quantity=3')
  expect(document.body).not.toHaveTextContent('secret-payment-key')
})

test.each([
  '/payments/toss/success?orderId=order-1&amount=120000',
  '/payments/toss/success?paymentKey=key&orderId=order-1&amount=0',
  '/payments/toss/success?paymentKey=key&orderId=order-1&amount=abc',
  '/payments/toss/success?paymentKey=key&orderId=&amount=120000',
])('잘못된 성공 리다이렉트 %s에서 인증 결과를 확인하지 못했다고 알린다', (path) => {
  renderResult(path)

  expect(screen.getByRole('alert')).toHaveTextContent('결제 인증 결과를 확인할 수 없습니다.')
  expect(screen.queryByText('결제 완료')).not.toBeInTheDocument()
})

test('결제창 취소 코드를 일반 실패와 구분해 안내한다', () => {
  renderResult('/payments/toss/fail?code=PAY_PROCESS_CANCELED&message=외부오류&saleId=11&quantity=3')

  expect(screen.getByRole('alert')).toHaveTextContent('결제를 취소했습니다.')
  expect(screen.getByRole('link', { name: '체크아웃으로 돌아가기' })).toHaveAttribute('href', '/checkout/11?quantity=3')
  expect(document.body).not.toHaveTextContent('외부오류')
})

test('일반 실패에서는 외부 메시지를 출력하지 않는다', () => {
  renderResult('/payments/toss/fail?code=UNKNOWN&message=신뢰할수없는문구&saleId=11&quantity=3')

  expect(screen.getByRole('alert')).toHaveTextContent('결제 인증에 실패했습니다.')
  expect(document.body).not.toHaveTextContent('신뢰할수없는문구')
})

test.each([
  '/payments/toss/fail?code=UNKNOWN&saleId=0&quantity=3',
  '/payments/toss/fail?code=UNKNOWN&saleId=11&quantity=2147483648',
  '/payments/toss/fail?code=UNKNOWN&saleId=11&quantity=3&quantity=4',
])('잘못된 복귀 매개변수 %s를 체크아웃 링크에 반영하지 않는다', (path) => {
  renderResult(path)

  expect(screen.queryByRole('link', { name: '체크아웃으로 돌아가기' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '상품 목록' })).toHaveAttribute('href', '/')
})
