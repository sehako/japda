import { useBuyerSales } from '../../features/buyer-sale/hook/useBuyerSales.ts'
import { BuyerSaleCalendar } from '../../features/buyer-sale/ui/BuyerSaleCalendar.tsx'
import { BuyerSaleList } from '../../features/buyer-sale/ui/BuyerSaleList.tsx'
import { imageBaseUrl } from '../../shared/config/env.ts'

function formatSelectedDate(date: string): string {
  const [year, month, day] = date.split('-').map(Number)
  return `${year}년 ${month}월 ${day}일`
}

export function BuyerMainPage() {
  const sales = useBuyerSales()
  const listTitle = sales.selectedDate === sales.today ? '오늘의 상품' : sales.selectedDate === sales.tomorrow ? '내일의 상품' : '판매 상품'
  const items = sales.data?.sales
  return <>
    <header className="h-16 border-b border-[var(--color-concrete-gray)] md:h-[72px]">
      <div className="mx-auto flex size-full max-w-[1440px] items-center justify-between px-5 md:px-8">
        <span className="text-xl font-semibold tracking-[-0.04em]" aria-label="JAPDA 홈">JAPDA</span>
        <button className="min-h-10 min-w-20 rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-[18px] font-medium text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3 md:min-h-[42px] md:min-w-[92px] md:px-[22px]" type="button">로그인</button>
      </div>
    </header>
    <main className="mx-auto w-full max-w-[1440px] px-5 pt-8 pb-16 md:px-8 md:pt-12 md:pb-24">
      <div className="border-t border-[var(--color-obsidian)] md:grid md:grid-cols-[minmax(260px,3fr)_minmax(0,7fr)]">
        <section className="border-b border-[var(--color-obsidian)] py-7 md:border-r md:border-b-0 md:border-[var(--color-concrete-gray)] md:py-10 md:pr-7 lg:pr-12" aria-labelledby="schedule-title">
          <div className="md:sticky md:top-8">
            <h1 className="mb-8 text-[clamp(28px,3vw,40px)] leading-[1.12] font-semibold tracking-[-0.04em] md:mb-6" id="schedule-title">판매 일정</h1>
            <BuyerSaleCalendar month={sales.month} selectedDate={sales.selectedDate} today={sales.today} onPreviousMonth={sales.showPreviousMonth} onNextMonth={sales.showNextMonth} onSelectDate={sales.selectDate} />
          </div>
        </section>
        <section className="min-w-0 py-12 md:py-10 md:pl-7 lg:pl-12" aria-labelledby="products-title">
          <header className="mb-7 flex items-end justify-between gap-8 max-[420px]:flex-col max-[420px]:items-start max-[420px]:gap-2">
            <div>
              <p className="mb-2 text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)]">{formatSelectedDate(sales.selectedDate)}</p>
              <h2 className="text-[clamp(26px,3.5vw,40px)] leading-[1.12] font-semibold tracking-[-0.04em]" id="products-title">{listTitle}</h2>
            </div>
            {items && items.length > 0 ? <p className="mb-1 text-xs font-semibold tracking-[0.1em] text-[var(--color-steel)]" aria-live="polite">{items.length}개 상품</p> : null}
          </header>
          <BuyerSaleList sales={items} isLoading={sales.isPending || sales.isFetching} isError={sales.isError} onRetry={() => void sales.retry()} imageBaseUrl={imageBaseUrl} />
        </section>
      </div>
    </main>
  </>
}
