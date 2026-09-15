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
