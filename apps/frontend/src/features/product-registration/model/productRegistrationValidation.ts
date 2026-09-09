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

function isValidDateTime(value: string) {
  return value !== '' && !Number.isNaN(new Date(value).getTime())
}

export function validateProductRegistrationField(
  draft: ProductRegistrationDraft,
  field: ProductRegistrationField,
): string | undefined {
  switch (field) {
    case 'name':
      return draft.name.trim() === '' ? '상품명을 입력해 주세요.' : undefined
    case 'description':
      return draft.description.trim() === ''
        ? '상품 설명을 입력해 주세요.'
        : undefined
    case 'images':
      if (draft.images.length === 0) {
        return '상품 이미지를 한 장 이상 선택해 주세요.'
      }

      return draft.primaryImageId !== null &&
        draft.images.some((image) => image.id === draft.primaryImageId)
        ? undefined
        : '대표 이미지를 지정해 주세요.'
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

      return undefined
    case 'quantity':
      return POSITIVE_INTEGER_PATTERN.test(draft.quantity)
        ? undefined
        : '판매 수량은 1 이상의 정수로 입력해 주세요.'
  }
}

export function validateProductRegistration(
  draft: ProductRegistrationDraft,
): ProductRegistrationValidationResult {
  const errors: ProductRegistrationErrors = {}

  for (const field of PRODUCT_REGISTRATION_FIELDS) {
    const error = validateProductRegistrationField(draft, field)

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
