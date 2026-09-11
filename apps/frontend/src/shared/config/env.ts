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
export const apiBaseUrl = import.meta.env?.VITE_API_BASE_URL ?? ''
export const imageBaseUrl = import.meta.env?.VITE_IMAGE_BASE_URL ?? ''
