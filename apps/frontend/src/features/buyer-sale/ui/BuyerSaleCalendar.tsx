import {
  addDays,
  buildCalendarDays,
  canMoveToNextMonth,
  isSelectableSaleDate,
} from '../model/buyerSale.ts'
import type { CalendarMonth } from '../model/buyerSale.ts'

interface BuyerSaleCalendarProps {
  month: CalendarMonth
  selectedDate: string
  today: string
  onPreviousMonth: () => void
  onNextMonth: () => void
  onSelectDate: (date: string) => void
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

function Chevron({ direction }: { direction: 'left' | 'right' }) {
  const path = direction === 'left' ? 'M11.25 3.75 6 9l5.25 5.25' : 'M6.75 3.75 12 9l-5.25 5.25'
  return <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d={path} stroke="currentColor" strokeWidth="1.5" /></svg>
}

export function BuyerSaleCalendar({ month, selectedDate, today, onPreviousMonth, onNextMonth, onSelectDate }: BuyerSaleCalendarProps) {
  const latestDate = addDays(today, 1)
  const days = buildCalendarDays(month.year, month.month)
  return <div className="border-y border-[var(--color-concrete-gray)]" aria-label="상품 판매 일정 달력">
    <div className="grid min-h-16 grid-cols-[48px_1fr_48px] items-center border-b border-[var(--color-concrete-gray)]">
      <button className="grid size-[42px] place-items-center border-0 bg-transparent hover:bg-[var(--color-soft-mist)] focus-visible:outline-2 focus-visible:outline-offset-2" type="button" aria-label="이전 달" onClick={onPreviousMonth}><Chevron direction="left" /></button>
      <h2 className="text-center text-lg font-semibold tracking-[-0.02em]" aria-live="polite">{month.year}년 {month.month}월</h2>
      <button className="grid size-[42px] place-items-center border-0 bg-transparent hover:bg-[var(--color-soft-mist)] focus-visible:outline-2 focus-visible:outline-offset-2 disabled:text-[var(--color-faint-gray)] disabled:hover:bg-transparent" type="button" aria-label="다음 달" disabled={!canMoveToNextMonth(month.year, month.month, latestDate)} onClick={onNextMonth}><Chevron direction="right" /></button>
    </div>
    <div className="grid grid-cols-7" aria-hidden="true">{WEEKDAYS.map((weekday) => <span className="px-1 pt-3.5 pb-2.5 text-center text-[11px] font-semibold tracking-[0.08em] text-[var(--color-steel)]" key={weekday}>{weekday}</span>)}</div>
    <div className="grid grid-cols-7" role="group" aria-label={`${month.year}년 ${month.month}월 날짜`}>
      {days.map((date, index) => <div className="aspect-square min-w-0 p-0.5" key={date ?? `empty-${index}`}>
        {date === null ? null : <button
          className="size-full min-h-10 border border-transparent bg-transparent p-0 hover:border-[var(--color-obsidian)] focus-visible:outline-2 focus-visible:outline-offset-1 disabled:border-transparent disabled:bg-[var(--color-soft-mist)] disabled:text-[var(--color-faint-gray)] aria-pressed:border-[var(--color-obsidian)] aria-pressed:bg-[var(--color-obsidian)] aria-pressed:text-white"
          type="button"
          aria-label={`${Number(date.slice(-2))}일${date === today ? ', 오늘' : ''}`}
          aria-pressed={date === selectedDate}
          disabled={!isSelectableSaleDate(date, today)}
          onClick={() => onSelectDate(date)}
        >{Number(date.slice(-2))}</button>}
      </div>)}
    </div>
  </div>
}
