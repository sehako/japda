import type {
  ProductRegistrationDraft,
  ProductRegistrationErrors,
  ProductRegistrationSubmissionState,
} from '../model/productRegistration'

type ProductRegistrationSummaryProps = {
  draft: ProductRegistrationDraft
  validationErrors: ProductRegistrationErrors
  submission: ProductRegistrationSubmissionState
  isDraftValid: boolean
  onReset: () => void
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-concrete-gray py-4 last:border-b-0">
      <dt className="text-body-sm text-steel">{label}</dt>
      <dd className="text-right text-body-sm font-medium text-obsidian">
        {value}
      </dd>
    </div>
  )
}

export function ProductRegistrationSummary({
  draft,
  validationErrors,
  submission,
  isDraftValid,
  onReset,
}: ProductRegistrationSummaryProps) {
  const hasProductError =
    validationErrors.name !== undefined ||
    validationErrors.description !== undefined
  const hasImageError = validationErrors.images !== undefined
  const hasSaleError =
    validationErrors.price !== undefined ||
    validationErrors.quantity !== undefined ||
    validationErrors.saleStartsAt !== undefined ||
    validationErrors.saleEndsAt !== undefined

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
          value={hasProductError ? '확인 필요' : '입력 완료'}
        />
        <SummaryRow
          label="선택 이미지"
          value={hasImageError ? `${draft.images.length}장 · 확인 필요` : `${draft.images.length}장`}
        />
        <SummaryRow
          label="판매 조건"
          value={hasSaleError ? '확인 필요' : '입력 완료'}
        />
      </dl>

      {submission.isComplete ? (
        <button
          type="button"
          className="mt-6 min-h-12 w-full rounded-button bg-obsidian px-6 py-3 text-body font-medium text-paper-white hover:bg-black"
          onClick={onReset}
        >
          새 상품 등록
        </button>
      ) : (
        <>
          <p className="mt-5 text-caption text-steel">
            모든 항목이 유효해야 상품을 등록할 수 있습니다.
          </p>
          <button
            type="submit"
            disabled={!isDraftValid || submission.isPending}
            className="mt-6 min-h-12 w-full rounded-button bg-obsidian px-6 py-3 text-body font-medium text-paper-white hover:bg-black disabled:cursor-not-allowed disabled:bg-soft-mist disabled:text-faint-gray"
          >
            {submission.isPending
              ? '상품 등록 중…'
              : submission.failureStage === null
                ? '상품 등록'
                : '다시 시도'}
          </button>
        </>
      )}
    </aside>
  )
}
