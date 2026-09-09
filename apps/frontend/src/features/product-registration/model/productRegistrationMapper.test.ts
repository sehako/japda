import { describe, expect, it } from 'vitest'
import type { ProductRegistrationDraft } from './productRegistration'
import {
  toCreateProductRequest,
  toCreateSaleRequest,
  toUploadProductImagesRequest,
} from './productRegistrationMapper'

const firstFile = new File(['first'], 'first.jpg', { type: 'image/jpeg' })
const secondFile = new File(['second'], 'second.png', { type: 'image/png' })

const draft: ProductRegistrationDraft = {
  name: '  한정판 상품  ',
  description: '  상품 설명  ',
  images: [
    { id: 'first', file: firstFile, previewUrl: 'blob:first' },
    { id: 'second', file: secondFile, previewUrl: 'blob:second' },
  ],
  primaryImageId: 'second',
  price: '120000',
  quantity: '10',
  saleStartsAt: '2026-09-09T10:00',
  saleEndsAt: '2026-09-10T10:00',
}

describe('상품 등록 요청 변환', () => {
  it('상품 문자열의 양쪽 공백을 제거한다', () => {
    expect(toCreateProductRequest(draft)).toEqual({
      name: '한정판 상품',
      description: '상품 설명',
    })
  })

  it('파일 순서와 대표 이미지의 0-based index를 유지한다', () => {
    expect(toUploadProductImagesRequest(draft)).toEqual({
      files: [firstFile, secondFile],
      representativeIndex: 1,
    })
  })

  it('숫자와 datetime-local 값을 API 요청 형식으로 변환한다', () => {
    expect(toCreateSaleRequest(draft)).toEqual({
      price: 120000,
      quantity: 10,
      startsAt: new Date('2026-09-09T10:00').toISOString(),
      endsAt: new Date('2026-09-10T10:00').toISOString(),
    })
  })
})
