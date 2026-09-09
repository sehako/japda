import { describe, expect, it } from 'vitest'
import {
  INITIAL_PRODUCT_REGISTRATION_DRAFT,
  type ProductRegistrationDraft,
  type ProductRegistrationImage,
} from './productRegistration'
import { validateProductRegistration } from './productRegistrationValidation'

const NOW = new Date('2026-09-09T00:00:00.000Z')

function image(
  name: string,
  options: { size?: number; type?: string } = {},
): ProductRegistrationImage {
  const file = new File([new Uint8Array(options.size ?? 1)], name, {
    type: options.type ?? 'image/jpeg',
  })

  return { id: name, file, previewUrl: `blob:${name}` }
}

function validDraft(
  overrides: Partial<ProductRegistrationDraft> = {},
): ProductRegistrationDraft {
  return {
    ...INITIAL_PRODUCT_REGISTRATION_DRAFT,
    name: '한정판 상품',
    description: '상품 설명',
    images: [image('product.jpg')],
    primaryImageId: 'product.jpg',
    price: '120000',
    quantity: '10',
    saleStartsAt: '2026-09-09T10:00',
    saleEndsAt: '2026-09-10T10:00',
    ...overrides,
  }
}

describe('validateProductRegistration', () => {
  it('상품명은 Unicode code point 기준 100자를 초과할 수 없다', () => {
    const result = validateProductRegistration(
      validDraft({ name: ` ${'😀'.repeat(101)} ` }),
      NOW,
    )

    expect(result.errors.name).toBe('상품명은 100자 이하로 입력해 주세요.')
  })

  it('상품 설명은 Unicode code point 기준 5,000자를 허용한다', () => {
    const result = validateProductRegistration(
      validDraft({ description: '가'.repeat(5_000) }),
      NOW,
    )

    expect(result.errors.description).toBeUndefined()
  })

  it('허용되지 않은 MIME 형식의 이미지를 거부한다', () => {
    const invalidImage = image('product.gif', { type: 'image/gif' })
    const result = validateProductRegistration(
      validDraft({ images: [invalidImage], primaryImageId: invalidImage.id }),
      NOW,
    )

    expect(result.errors.images).toBe(
      'JPEG, PNG 또는 WebP 이미지만 선택해 주세요.',
    )
  })

  it('이미지는 최대 10장까지만 허용한다', () => {
    const images = Array.from({ length: 11 }, (_, index) =>
      image(`product-${index}.jpg`),
    )
    const result = validateProductRegistration(
      validDraft({ images, primaryImageId: images[0].id }),
      NOW,
    )

    expect(result.errors.images).toBe('상품 이미지는 최대 10장까지 선택해 주세요.')
  })

  it('개별 이미지가 10 MiB를 초과하면 거부한다', () => {
    const oversizedImage = image('large.jpg', { size: 10 * 1024 * 1024 + 1 })
    const result = validateProductRegistration(
      validDraft({
        images: [oversizedImage],
        primaryImageId: oversizedImage.id,
      }),
      NOW,
    )

    expect(result.errors.images).toBe(
      '이미지 한 장의 크기는 10 MiB 이하여야 합니다.',
    )
  })

  it('전체 이미지가 50 MiB를 초과하면 거부한다', () => {
    const images = Array.from({ length: 6 }, (_, index) =>
      image(`large-${index}.jpg`, { size: 9 * 1024 * 1024 }),
    )
    const result = validateProductRegistration(
      validDraft({ images, primaryImageId: images[0].id }),
      NOW,
    )

    expect(result.errors.images).toBe(
      '전체 이미지 크기는 50 MiB 이하여야 합니다.',
    )
  })

  it('가격과 수량은 0이 아닌 양의 정수여야 한다', () => {
    const result = validateProductRegistration(
      validDraft({ price: '0', quantity: '1.5' }),
      NOW,
    )

    expect(result.errors.price).toBe('가격은 1원 이상의 정수로 입력해 주세요.')
    expect(result.errors.quantity).toBe(
      '판매 수량은 1 이상의 정수로 입력해 주세요.',
    )
  })

  it('판매 종료 시각은 시작 시각보다 늦고 현재보다 미래여야 한다', () => {
    const result = validateProductRegistration(
      validDraft({
        saleStartsAt: '2026-09-08T10:00',
        saleEndsAt: '2026-09-08T11:00',
      }),
      NOW,
    )

    expect(result.errors.saleEndsAt).toBe(
      '판매 종료 시각은 현재보다 이후여야 합니다.',
    )
  })
})
