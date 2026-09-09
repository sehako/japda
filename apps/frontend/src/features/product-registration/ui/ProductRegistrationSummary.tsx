import type { ProductRegistrationDraft } from '../model/productRegistration'

type ProductRegistrationSummaryProps = {
  draft: ProductRegistrationDraft
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-concrete-gray py-4 last:border-b-0">
      <dt className="text-body-sm text-steel">{label}</dt>
      <dd className="text-right text-body-sm font-medium text-obsidian">{value}</dd>
    </div>
  )
}

export function ProductRegistrationSummary({
  draft,
}: ProductRegistrationSummaryProps) {
  const hasProductInformation =
    draft.name.trim() !== '' && draft.description.trim() !== ''
  const hasSaleInformation =
    draft.price !== '' &&
    draft.quantity !== '' &&
    draft.saleStartsAt !== '' &&
    draft.saleEndsAt !== ''

  return (
    <aside className="border border-obsidian bg-paper-white p-5 sm:p-7 lg:sticky lg:top-8">
      <p className="text-caption font-medium tracking-[0.14em] text-steel uppercase">
        등록 요약
      </p>
      <h2 className="mt-2 text-subheading font-medium text-obsidian">
        입력 상태
      </h2>

      <dl className="mt-5 border-y border-concrete-gray">
        <SummaryRow
          label="상품 정보"
          value={hasProductInformation ? '입력됨' : '확인 필요'}
        />
        <SummaryRow label="선택 이미지" value={`${draft.images.length}장`} />
        <SummaryRow
          label="판매 조건"
          value={hasSaleInformation ? '입력됨' : '확인 필요'}
        />
      </dl>

      <p className="mt-5 text-caption text-steel">
        입력 내용만 검증하며 실제 상품을 저장하거나 등록하지 않습니다.
      </p>
      <button
        type="submit"
        className="mt-6 min-h-12 w-full rounded-button bg-obsidian px-6 py-3 text-body font-medium text-paper-white hover:bg-black"
      >
        입력 내용 검증
      </button>
    </aside>
  )
}
