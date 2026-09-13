import { ANONYMOUS, loadTossPayments } from '@tosspayments/tosspayments-sdk'
import type { WidgetAgreementWidget, WidgetPaymentMethodWidget } from '@tosspayments/tosspayments-sdk'

export interface TossPaymentWidget {
  setAmount(amount: { currency: 'KRW'; value: number }): Promise<void>
  renderPaymentMethods(options: { selector: string }): Promise<{ on(event: 'paymentMethodSelect', callback: (selected: boolean) => void): void }>
  renderAgreement(options: { selector: string }): Promise<{ on(event: 'agreementStatusChange', callback: (agreed: boolean) => void): void }>
  hasSelectedPaymentMethod(): Promise<boolean>
  requestPayment(options: { orderId: string; orderName: string; successUrl: string; failUrl: string }): Promise<void>
  destroy(): Promise<void>
}

export async function createTossPaymentWidget(clientKey: string): Promise<TossPaymentWidget> {
  const tossPayments = await loadTossPayments(clientKey)
  const widgets = tossPayments.widgets({ customerKey: ANONYMOUS })
  let methodsWidget: WidgetPaymentMethodWidget | null = null
  let agreementWidget: WidgetAgreementWidget | null = null
  let methodsRender: Promise<unknown> | null = null
  let agreementRender: Promise<unknown> | null = null
  let destroyed = false
  let cleanup: Promise<void> | null = null

  return {
    setAmount: (amount) => widgets.setAmount(amount),
    async renderPaymentMethods(options) {
      if (destroyed) throw new Error('결제 위젯이 정리되었습니다.')
      const rendering = widgets.renderPaymentMethods(options).then(async (rendered) => {
        if (destroyed) {
          await rendered.destroy()
          throw new Error('결제 위젯이 정리되었습니다.')
        }
        methodsWidget = rendered
        return { on: (event: 'paymentMethodSelect', callback: (selected: boolean) => void) => rendered.on(event, (paymentMethod) => callback(Boolean(paymentMethod?.code))) }
      })
      methodsRender = rendering
      return rendering
    },
    async renderAgreement(options) {
      if (destroyed) throw new Error('결제 위젯이 정리되었습니다.')
      const rendering = widgets.renderAgreement(options).then(async (rendered) => {
        if (destroyed) {
          await rendered.destroy()
          throw new Error('결제 위젯이 정리되었습니다.')
        }
        agreementWidget = rendered
        return { on: (event: 'agreementStatusChange', callback: (agreed: boolean) => void) => rendered.on(event, (status) => callback(status.agreedRequiredTerms)) }
      })
      agreementRender = rendering
      return rendering
    },
    async hasSelectedPaymentMethod() {
      try {
        const selected = await methodsWidget?.getSelectedPaymentMethod()
        return Boolean(selected?.code)
      } catch (error) {
        if (typeof error === 'object' && error !== null && 'code' in error && error.code === 'NOT_SELECTED_PAYMENT_METHOD') return false
        throw error
      }
    },
    async requestPayment(options) {
      await widgets.requestPayment(options)
    },
    destroy() {
      if (cleanup) return cleanup
      destroyed = true
      cleanup = (async () => {
        await Promise.allSettled([methodsRender, agreementRender].filter((render): render is Promise<unknown> => render !== null))
        await Promise.allSettled([methodsWidget?.destroy(), agreementWidget?.destroy()].filter((result): result is Promise<void> => result !== undefined))
      })()
      return cleanup
    },
  }
}
