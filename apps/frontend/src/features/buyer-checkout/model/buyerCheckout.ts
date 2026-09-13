export interface ShippingAddress {
  shippingAddressId: number
  addressName: string
  recipientName: string
  phoneNumber: string
  postalCode: string
  address: string
  detailAddress: string
  deliveryMessage: string | null
}

export interface BuyerCheckout {
  saleId: number
  productName: string
  representativeImagePath: string
  quantity: number
  unitPrice: number
  totalPrice: number
  shippingAddresses: ShippingAddress[]
}

export type CreateShippingAddressRequest = Omit<ShippingAddress, 'shippingAddressId'>

export function parseCheckoutQuantity(value: string | null): number | null {
  if (!value || !/^[1-9]\d*$/.test(value)) return null
  const quantity = Number(value)
  return Number.isSafeInteger(quantity) && quantity <= 2_147_483_647 ? quantity : null
}

export type ShippingAddressField = keyof CreateShippingAddressRequest

export const shippingAddressFields: Array<{ name: ShippingAddressField; label: string; maxLength: number; required: boolean }> = [
  { name: 'addressName', label: '배송지명', maxLength: 100, required: true },
  { name: 'recipientName', label: '수취인명', maxLength: 100, required: true },
  { name: 'phoneNumber', label: '전화번호', maxLength: 30, required: true },
  { name: 'postalCode', label: '우편번호', maxLength: 20, required: true },
  { name: 'address', label: '기본 주소', maxLength: 255, required: true },
  { name: 'detailAddress', label: '상세 주소', maxLength: 255, required: true },
  { name: 'deliveryMessage', label: '배송 메모', maxLength: 500, required: false },
]

export function validateShippingAddress(value: CreateShippingAddressRequest): Partial<Record<ShippingAddressField, string>> {
  const errors: Partial<Record<ShippingAddressField, string>> = {}
  for (const field of shippingAddressFields) {
    const length = value[field.name]?.trim().length ?? 0
    if ((field.required && length === 0) || length > field.maxLength) {
      errors[field.name] = field.required
        ? `${field.label}은(는) 1~${field.maxLength}자여야 합니다.`
        : `${field.label}는 ${field.maxLength}자 이하여야 합니다.`
    }
  }
  return errors
}
