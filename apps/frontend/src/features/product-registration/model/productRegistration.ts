import { ApiError, DEFAULT_API_ERROR_MESSAGE } from '../../../shared/api/apiClient.ts'

const MAX_IMAGE_COUNT = 10
const MAX_IMAGE_SIZE = 10 * 1024 * 1024
const MAX_TOTAL_IMAGE_SIZE = 50 * 1024 * 1024
const ALLOWED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])

export interface RegistrationInput {
  name: string
  description: string
  files: File[]
  representativeIndex: number | null
}

export interface NormalizedRegistrationInput {
  name: string
  description: string | null
  files: File[]
  representativeIndex: number
}

export interface ProductRegistrationFieldErrors {
  name?: string
  description?: string
  files?: string
  representativeIndex?: string
}

export type ValidationResult =
  | { valid: true; value: NormalizedRegistrationInput }
  | { valid: false; errors: ProductRegistrationFieldErrors }

export type SubmissionStage = 'input' | 'creating-product' | 'registering-images' | 'retry' | 'blocked' | 'success'

export interface SelectedProductImage {
  file: File
  previewUrl: string
}

export function validateProductRegistration(input: RegistrationInput): ValidationResult {
  const errors: ProductRegistrationFieldErrors = {}
  const name = input.name.trim()
  const description = input.description.trim()

  if (name.length === 0) errors.name = '상품명을 입력해 주세요.'
  else if (name.length > 100) errors.name = '상품명은 100자 이하로 입력해 주세요.'
  if (description.length > 3000) errors.description = '상품 설명은 3000자 이하로 입력해 주세요.'

  if (input.files.length < 1 || input.files.length > MAX_IMAGE_COUNT) {
    errors.files = '이미지는 1장 이상 10장 이하로 선택해 주세요.'
  } else {
    const messages: string[] = []
    if (input.files.some((file) => !ALLOWED_IMAGE_TYPES.has(file.type))) {
      messages.push('JPEG, PNG, WebP 이미지만 선택할 수 있습니다.')
    }
    if (input.files.some((file) => file.size < 1)) messages.push('빈 파일은 선택할 수 없습니다.')
    if (input.files.some((file) => file.size > MAX_IMAGE_SIZE)) messages.push('이미지 한 장은 10MiB 이하여야 합니다.')
    if (input.files.reduce((total, file) => total + file.size, 0) > MAX_TOTAL_IMAGE_SIZE) {
      messages.push('전체 이미지 용량은 50MiB 이하여야 합니다.')
    }
    if (messages.length > 0) errors.files = messages.join(' ')
  }

  if (input.representativeIndex === null
    || input.representativeIndex < 0
    || input.representativeIndex >= input.files.length) {
    errors.representativeIndex = '대표 이미지를 지정해 주세요.'
  }

  if (Object.keys(errors).length > 0) return { valid: false, errors }
  return {
    valid: true,
    value: { name, description: description || null, files: input.files, representativeIndex: input.representativeIndex as number },
  }
}

export function adjustRepresentativeIndexAfterRemoval(
  representativeIndex: number | null,
  removedIndex: number,
  previousCount: number,
): number | null {
  if (previousCount <= 1) return null
  if (representativeIndex === null || representativeIndex === removedIndex) return 0
  return removedIndex < representativeIndex ? representativeIndex - 1 : representativeIndex
}

export interface MappedRegistrationErrors {
  fieldErrors: ProductRegistrationFieldErrors
  formError: string | null
  blocksSubmission: boolean
}

export function mapApiErrorToRegistrationErrors(error: unknown): MappedRegistrationErrors {
  if (!(error instanceof ApiError)) {
    return { fieldErrors: {}, formError: DEFAULT_API_ERROR_MESSAGE, blocksSubmission: false }
  }
  if (error.code === 'PRODUCT_IMAGES_ALREADY_REGISTERED') {
    return {
      fieldErrors: {},
      formError: '이미지가 이미 등록되었거나 이전 요청이 완료되었습니다. 상품 상태를 확인해 주세요.',
      blocksSubmission: true,
    }
  }

  const fieldErrors: ProductRegistrationFieldErrors = {}
  const formMessages: string[] = []
  for (const [property, message] of Object.entries(error.fieldErrors)) {
    if (property === 'name' || property === 'description' || property === 'files' || property === 'representativeIndex') {
      fieldErrors[property] = message
    } else {
      formMessages.push(message)
    }
  }
  const formError = formMessages[0] ?? (Object.keys(fieldErrors).length === 0 ? error.detail ?? DEFAULT_API_ERROR_MESSAGE : null)
  return { fieldErrors, formError, blocksSubmission: false }
}
