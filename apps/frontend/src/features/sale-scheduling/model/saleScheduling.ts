import { ApiError, DEFAULT_API_ERROR_MESSAGE } from '../../../shared/api/apiClient.ts'

export type ProductSort = 'latest' | 'oldest' | 'name-asc' | 'name-desc'

export interface ReadyProduct {
  id: number
  name: string
}

export interface CreateSaleRequest {
  productId: number
  saleDate: string
  price: number
  quantity: number
}

export interface CreateSaleResponse extends CreateSaleRequest {
  id: number
  sellerId: number
  startsAt: string
  endsAt: string
  createdAt: string
}

export interface SaleSchedulingInput {
  productId: number | null
  saleDate: string
  price: string
  quantity: string
}

export interface SaleSchedulingFieldErrors {
  productId?: string
  saleDate?: string
  price?: string
  quantity?: string
}

export interface RegistrationWindow {
  isOpen: boolean
  saleDate: string | null
  title: string
  description: string
}

const KST_OFFSET = 9 * 60 * 60 * 1000
const INTEGER_PATTERN = /^\d+$/

function datePartsInKst(now: Date) {
  const kst = new Date(now.getTime() + KST_OFFSET)
  return { year: kst.getUTCFullYear(), month: kst.getUTCMonth() + 1, day: kst.getUTCDate(), hour: kst.getUTCHours() }
}

function formatDate(year: number, month: number, day: number) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

export function getRegistrationWindow(now = new Date()): RegistrationWindow {
  const current = datePartsInKst(now)
  if (current.hour < 9) {
    return {
      isOpen: false,
      saleDate: null,
      title: '오늘 09:00부터 등록할 수 있습니다.',
      description: '한국 표준시 기준 09:00부터 다음 날 판매 일정을 등록할 수 있습니다.',
    }
  }
  const tomorrow = new Date(Date.UTC(current.year, current.month - 1, current.day + 1))
  const saleDate = formatDate(tomorrow.getUTCFullYear(), tomorrow.getUTCMonth() + 1, tomorrow.getUTCDate())
  return {
    isOpen: true,
    saleDate,
    title: `${tomorrow.getUTCFullYear()}년 ${tomorrow.getUTCMonth() + 1}월 ${tomorrow.getUTCDate()}일 판매 일정을 등록할 수 있습니다.`,
    description: '오늘 09:00부터 24:00 전까지 등록할 수 있습니다.',
  }
}

function parseInteger(value: string, max: number): number | null {
  if (!INTEGER_PATTERN.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 1 && parsed <= max ? parsed : null
}

export type SaleValidationResult =
  | { valid: true; value: CreateSaleRequest }
  | { valid: false; errors: SaleSchedulingFieldErrors }

export function validateSaleScheduling(input: SaleSchedulingInput, availableSaleDate: string | null): SaleValidationResult {
  const errors: SaleSchedulingFieldErrors = {}
  if (input.productId === null || !Number.isSafeInteger(input.productId) || input.productId <= 0) errors.productId = '판매할 상품을 선택해 주세요.'
  if (availableSaleDate === null || input.saleDate !== availableSaleDate) errors.saleDate = '현재 등록 가능한 판매일을 확인해 주세요.'
  const price = parseInteger(input.price, Number.MAX_SAFE_INTEGER)
  const quantity = parseInteger(input.quantity, 2_147_483_647)
  if (price === null) errors.price = '판매 가격은 1 이상의 정수로 입력해 주세요.'
  if (quantity === null) errors.quantity = '판매 수량은 1 이상 2,147,483,647 이하의 정수로 입력해 주세요.'
  if (Object.keys(errors).length > 0) return { valid: false, errors }
  return { valid: true, value: { productId: input.productId as number, saleDate: input.saleDate, price: price as number, quantity: quantity as number } }
}

export interface MappedSaleErrors {
  fieldErrors: SaleSchedulingFieldErrors
  formError: string | null
  refreshProducts: boolean
}

export function mapApiErrorToSaleErrors(error: unknown): MappedSaleErrors {
  if (!(error instanceof ApiError)) return { fieldErrors: {}, formError: DEFAULT_API_ERROR_MESSAGE, refreshProducts: false }
  const message = error.detail ?? error.message
  if (error.code === 'SALE_PRODUCT_NOT_FOUND' || error.code === 'SALE_PRODUCT_NOT_READY') {
    return { fieldErrors: { productId: message }, formError: null, refreshProducts: true }
  }
  if (error.code === 'SALE_REGISTRATION_CLOSED' || error.code === 'SALE_CAPACITY_EXCEEDED') {
    return { fieldErrors: { saleDate: message }, formError: null, refreshProducts: false }
  }
  if (error.code === 'SALE_PRICE_INVALID') return { fieldErrors: { price: message }, formError: null, refreshProducts: false }
  if (error.code === 'SALE_QUANTITY_INVALID') return { fieldErrors: { quantity: message }, formError: null, refreshProducts: false }
  if (error.code === 'SALE_SELLER_ALREADY_REGISTERED') {
    return { fieldErrors: {}, formError: '같은 판매일에는 이미 등록한 일정이 있습니다.', refreshProducts: false }
  }
  const fieldErrors: SaleSchedulingFieldErrors = {}
  const formMessages: string[] = []
  for (const [field, fieldMessage] of Object.entries(error.fieldErrors)) {
    if (field === 'productId' || field === 'saleDate' || field === 'price' || field === 'quantity') fieldErrors[field] = fieldMessage
    else formMessages.push(fieldMessage)
  }
  return { fieldErrors, formError: formMessages[0] ?? (Object.keys(fieldErrors).length === 0 ? error.detail ?? DEFAULT_API_ERROR_MESSAGE : null), refreshProducts: false }
}
