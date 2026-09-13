export const SELLER_ID_CONFIG_ERROR = '개발용 판매자 식별자 설정을 확인해 주세요.'

export type SellerIdConfig =
  | { valid: true; value: number }
  | { valid: false; error: string }

export function parseSellerId(value: string | undefined): SellerIdConfig {
  if (!value || !/^\d+$/.test(value)) return { valid: false, error: SELLER_ID_CONFIG_ERROR }
  const sellerId = Number(value)
  if (!Number.isSafeInteger(sellerId) || sellerId <= 0) return { valid: false, error: SELLER_ID_CONFIG_ERROR }
  return { valid: true, value: sellerId }
}

export const sellerIdConfig = parseSellerId(import.meta.env?.VITE_SELLER_ID)
export const BUYER_ID_CONFIG_ERROR = '개발용 구매자 식별자 설정을 확인해 주세요.'

export function parseBuyerId(value: string | undefined): SellerIdConfig {
  if (!value || !/^\d+$/.test(value)) return { valid: false, error: BUYER_ID_CONFIG_ERROR }
  const buyerId = Number(value)
  if (!Number.isSafeInteger(buyerId) || buyerId <= 0) return { valid: false, error: BUYER_ID_CONFIG_ERROR }
  return { valid: true, value: buyerId }
}

export const buyerIdConfig = parseBuyerId(import.meta.env?.VITE_BUYER_ID)
export const TOSS_PAYMENT_PREVIEW_CONFIG_ERROR = '테스트 결제 설정을 확인해 주세요.'

export type TossPaymentPreviewConfig =
  | { valid: true; clientKey: string }
  | { valid: false; error: string }

export function parseTossPaymentPreview(enabled: string | undefined, clientKey: string | undefined): TossPaymentPreviewConfig {
  if (enabled !== 'true' || !clientKey?.startsWith('test')) return { valid: false, error: TOSS_PAYMENT_PREVIEW_CONFIG_ERROR }
  return { valid: true, clientKey }
}

export const tossPaymentPreviewConfig = parseTossPaymentPreview(
  import.meta.env?.VITE_TOSS_PAYMENT_PREVIEW_ENABLED,
  import.meta.env?.VITE_TOSS_CLIENT_KEY,
)
export const apiBaseUrl = import.meta.env?.VITE_API_BASE_URL ?? ''
export const imageBaseUrl = import.meta.env?.VITE_IMAGE_BASE_URL ?? ''
