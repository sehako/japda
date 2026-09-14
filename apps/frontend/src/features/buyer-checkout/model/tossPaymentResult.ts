export interface TossSuccessParameters {
  paymentKey: string
  orderId: string
  amount: number
}

export function parseTossSuccessParameters(params: URLSearchParams): TossSuccessParameters | null {
  if (params.getAll('paymentKey').length !== 1 || params.getAll('orderId').length !== 1 || params.getAll('amount').length !== 1) return null
  const paymentKey = params.get('paymentKey')
  const orderId = params.get('orderId')
  const amount = params.get('amount')
  if (!paymentKey?.trim() || paymentKey.length > 200 || !orderId || !/^[A-Za-z0-9_=-]{6,64}$/.test(orderId) || !amount || !/^[1-9]\d*$/.test(amount)) return null
  const numericAmount = Number(amount)
  if (!Number.isSafeInteger(numericAmount)) return null
  return { paymentKey, orderId, amount: numericAmount }
}

export function hasTossSuccessParameters(params: URLSearchParams): boolean {
  return parseTossSuccessParameters(params) !== null
}
