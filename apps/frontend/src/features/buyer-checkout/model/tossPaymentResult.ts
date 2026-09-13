export function hasTossSuccessParameters(params: URLSearchParams): boolean {
  if (params.getAll('paymentKey').length !== 1 || params.getAll('orderId').length !== 1 || params.getAll('amount').length !== 1) return false
  const paymentKey = params.get('paymentKey')
  const orderId = params.get('orderId')
  const amount = params.get('amount')
  if (!paymentKey?.trim() || !orderId?.trim() || !amount || !/^[1-9]\d*$/.test(amount)) return false
  return Number.isSafeInteger(Number(amount))
}
