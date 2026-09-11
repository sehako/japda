export type BuyerSaleStatus = 'UPCOMING' | 'ON_SALE' | 'ENDED'

export interface BuyerSaleProduct {
  saleId: number
  productId: number
  name: string
  description: string | null
  price: number
  quantity: number
  saleDate: string
  startsAt: string
  endsAt: string
  status: BuyerSaleStatus
  representativeImagePath: string
}

export interface BuyerSaleProductListResponse {
  sales: BuyerSaleProduct[]
}

export interface CalendarMonth {
  year: number
  month: number
}

const KOREAN_TIME_ZONE = 'Asia/Seoul'
const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

function formatDate(year: number, month: number, day: number): string {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function parseDate(value: string): { year: number; month: number; day: number } | null {
  const match = DATE_PATTERN.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(Date.UTC(year, month - 1, day))
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null
  return { year, month, day }
}

export function getKoreanToday(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: KOREAN_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]))
  return `${values.year}-${values.month}-${values.day}`
}

export function addDays(dateValue: string, amount: number): string {
  const parsed = parseDate(dateValue)
  if (!parsed) throw new Error('유효한 날짜가 필요합니다.')
  const date = new Date(Date.UTC(parsed.year, parsed.month - 1, parsed.day + amount))
  return formatDate(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate())
}

export function isSelectableSaleDate(dateValue: string, today: string): boolean {
  return parseDate(dateValue) !== null && dateValue <= addDays(today, 1)
}

export function buildCalendarDays(year: number, month: number): Array<string | null> {
  const firstWeekday = new Date(Date.UTC(year, month - 1, 1)).getUTCDay()
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const days = [
    ...Array<string | null>(firstWeekday).fill(null),
    ...Array.from({ length: lastDay }, (_, index) => formatDate(year, month, index + 1)),
  ]
  return [...days, ...Array<string | null>((7 - days.length % 7) % 7).fill(null)]
}

export function getMonthFromDate(dateValue: string): CalendarMonth {
  const parsed = parseDate(dateValue)
  if (!parsed) throw new Error('유효한 날짜가 필요합니다.')
  return { year: parsed.year, month: parsed.month }
}

export function moveMonth(current: CalendarMonth, amount: number): CalendarMonth {
  const date = new Date(Date.UTC(current.year, current.month - 1 + amount, 1))
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1 }
}

export function canMoveToNextMonth(year: number, month: number, latestDate: string): boolean {
  const nextMonth = moveMonth({ year, month }, 1)
  return formatDate(nextMonth.year, nextMonth.month, 1) <= latestDate
}

export function millisecondsUntilNextKoreanMidnight(now = new Date()): number {
  const today = parseDate(getKoreanToday(now))
  if (!today) return 0
  const nextMidnight = Date.UTC(today.year, today.month - 1, today.day + 1) - 9 * 60 * 60 * 1000
  return Math.max(0, nextMidnight - now.getTime())
}

export function formatKoreanPrice(price: number): string {
  return `${new Intl.NumberFormat('ko-KR').format(price)}원`
}

export function getSaleStatusLabel(status: BuyerSaleStatus): string {
  return status === 'ON_SALE' ? 'LIVE' : status
}

export function buildProductImageUrl(baseUrl: string, relativePath: string): string | null {
  if (!baseUrl || !relativePath.startsWith('/')) return null
  try {
    const parsedBaseUrl = new URL(baseUrl)
    if (parsedBaseUrl.protocol !== 'http:' && parsedBaseUrl.protocol !== 'https:') return null
    return `${baseUrl.replace(/\/+$/, '')}/${relativePath.replace(/^\/+/, '')}`
  } catch {
    return null
  }
}
