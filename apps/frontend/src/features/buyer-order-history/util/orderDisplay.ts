const wonFormatter = new Intl.NumberFormat('ko-KR', {
  maximumFractionDigits: 0,
})

const dateTimeFormatter = new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

export function formatWon(amount: number): string {
  return `${wonFormatter.format(amount)}원`
}

export function formatOrderDateTime(value: string): string {
  return dateTimeFormatter.format(new Date(value))
}
