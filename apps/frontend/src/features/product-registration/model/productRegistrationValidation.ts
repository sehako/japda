import type {
  ProductRegistrationDraft,
  ProductRegistrationErrors,
  ProductRegistrationField,
} from './productRegistration'
import { PRODUCT_REGISTRATION_FIELDS } from './productRegistration'

export type ProductRegistrationValidationResult = {
  errors: ProductRegistrationErrors
  firstErrorField: ProductRegistrationField | null
}

const POSITIVE_INTEGER_PATTERN = /^[1-9]\d*$/
const ALLOWED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
const MAX_IMAGE_COUNT = 10
const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
const MAX_TOTAL_IMAGE_SIZE_BYTES = 50 * 1024 * 1024

function codePointLength(value: string) {
  return Array.from(value).length
}

function isValidDateTime(value: string) {
  return value !== '' && !Number.isNaN(new Date(value).getTime())
}

export function validateProductRegistrationImages(
  images: readonly { file: File }[],
): string | undefined {
  if (images.length === 0) {
    return '상품 이미지를 한 장 이상 선택해 주세요.'
  }

  if (images.length > MAX_IMAGE_COUNT) {
    return '상품 이미지는 최대 10장까지 선택해 주세요.'
  }

  if (images.some((image) => !ALLOWED_IMAGE_TYPES.has(image.file.type))) {
    return 'JPEG, PNG 또는 WebP 이미지만 선택해 주세요.'
  }

  if (images.some((image) => image.file.size > MAX_IMAGE_SIZE_BYTES)) {
    return '이미지 한 장의 크기는 10 MiB 이하여야 합니다.'
  }

  if (
    images.reduce((total, image) => total + image.file.size, 0) >
    MAX_TOTAL_IMAGE_SIZE_BYTES
  ) {
    return '전체 이미지 크기는 50 MiB 이하여야 합니다.'
  }

  return undefined
}

export function validateProductRegistrationField(
  draft: ProductRegistrationDraft,
  field: ProductRegistrationField,
  now = new Date(),
): string | undefined {
  switch (field) {
    case 'name': {
      const name = draft.name.trim()

      if (name === '') {
        return '상품명을 입력해 주세요.'
      }

      return codePointLength(name) > 100
        ? '상품명은 100자 이하로 입력해 주세요.'
        : undefined
    }
    case 'description': {
      const description = draft.description.trim()

      if (description === '') {
        return '상품 설명을 입력해 주세요.'
      }

      return codePointLength(description) > 5_000
        ? '상품 설명은 5,000자 이하로 입력해 주세요.'
        : undefined
    }
    case 'images': {
      const imageError = validateProductRegistrationImages(draft.images)

      if (imageError !== undefined) {
        return imageError
      }

      return draft.primaryImageId !== null &&
        draft.images.some((image) => image.id === draft.primaryImageId)
        ? undefined
        : '대표 이미지를 지정해 주세요.'
    }
    case 'price':
      return POSITIVE_INTEGER_PATTERN.test(draft.price)
        ? undefined
        : '가격은 1원 이상의 정수로 입력해 주세요.'
    case 'saleStartsAt':
      return isValidDateTime(draft.saleStartsAt)
        ? undefined
        : '판매 시작 시각을 입력해 주세요.'
    case 'saleEndsAt':
      if (!isValidDateTime(draft.saleEndsAt)) {
        return '판매 종료 시각을 입력해 주세요.'
      }

      if (
        isValidDateTime(draft.saleStartsAt) &&
        new Date(draft.saleEndsAt).getTime() <=
          new Date(draft.saleStartsAt).getTime()
      ) {
        return '판매 종료 시각은 시작 시각보다 이후여야 합니다.'
      }

      if (new Date(draft.saleEndsAt).getTime() <= now.getTime()) {
        return '판매 종료 시각은 현재보다 이후여야 합니다.'
      }

      return undefined
    case 'quantity':
      return POSITIVE_INTEGER_PATTERN.test(draft.quantity)
        ? undefined
        : '판매 수량은 1 이상의 정수로 입력해 주세요.'
  }
}

export function validateProductRegistration(
  draft: ProductRegistrationDraft,
  now = new Date(),
): ProductRegistrationValidationResult {
  const errors: ProductRegistrationErrors = {}

  for (const field of PRODUCT_REGISTRATION_FIELDS) {
    const error = validateProductRegistrationField(draft, field, now)

    if (error !== undefined) {
      errors[field] = error
    }
  }

  return {
    errors,
    firstErrorField:
      PRODUCT_REGISTRATION_FIELDS.find((field) => errors[field]) ?? null,
  }
}
