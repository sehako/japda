import { useEffect, useRef } from 'react'

import { ProductImageField, type ProductRegistrationImage } from './ProductImageField'

export type ProductRegistrationStage = 'input' | 'creating-product' | 'registering-images' | 'retry' | 'blocked' | 'success'
export interface ProductRegistrationFieldErrors { name?: string; description?: string; files?: string; representativeIndex?: string }

export interface ProductRegistrationFormProps {
  name: string; description: string; images: ProductRegistrationImage[]; representativeIndex: number | null
  submissionStage: ProductRegistrationStage; fieldErrors?: ProductRegistrationFieldErrors; formError?: string; createdProductId?: number | null
  onNameChange: (value: string) => void; onDescriptionChange: (value: string) => void; onImagesAdd: (files: File[]) => void
  onImageRemove: (index: number) => void; onRepresentativeSelect: (index: number) => void; onSubmit: () => void; onCancel: () => void
  onScheduleSale: () => void; onStartNew: () => void
}

const SUBMISSION_MESSAGES: Partial<Record<ProductRegistrationStage, string>> = { 'creating-product': '상품 정보를 저장하는 중', 'registering-images': '이미지를 등록하는 중' }

export function ProductRegistrationForm({ name, description, images, representativeIndex, submissionStage, fieldErrors = {}, formError, createdProductId, onNameChange, onDescriptionChange, onImagesAdd, onImageRemove, onRepresentativeSelect, onSubmit, onCancel, onScheduleSale, onStartNew }: ProductRegistrationFormProps) {
  const formRef = useRef<HTMLFormElement>(null)
  const isSubmitting = submissionStage === 'creating-product' || submissionStage === 'registering-images'
  const isRetryPending = submissionStage === 'retry'
  const isInputLocked = isSubmitting || isRetryPending || submissionStage === 'blocked'
  const imageError = fieldErrors.files ?? fieldErrors.representativeIndex

  useEffect(() => {
    formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [fieldErrors])

  if (submissionStage === 'success') {
    return <section className="border-y border-[var(--color-obsidian)] py-12 md:py-16" aria-labelledby="registration-complete-heading">
      <p className="mb-2 text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)]">REGISTRATION COMPLETE</p>
      <h2 id="registration-complete-heading" className="text-2xl font-semibold">상품 등록이 완료되었습니다.</h2>
      <dl className="mt-8 grid max-w-md grid-cols-[auto_1fr] gap-x-8 gap-y-3 border-y border-[var(--color-concrete-gray)] py-5 text-sm"><dt className="text-[var(--color-steel)]">상품 식별자</dt><dd className="font-medium">{createdProductId}</dd><dt className="text-[var(--color-steel)]">상품 상태</dt><dd className="font-medium">READY</dd></dl>
      <div className="mt-8 flex flex-wrap gap-3">
        <button className="min-h-12 rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-7 font-medium text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3" type="button" onClick={onScheduleSale}>판매 일정 등록</button>
        <button className="min-h-12 rounded-full border border-[var(--color-obsidian)] bg-white px-7 font-medium text-[var(--color-obsidian)] hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3" type="button" onClick={onStartNew}>새 상품 등록</button>
      </div>
    </section>
  }

  return <form ref={formRef} noValidate onSubmit={(event) => { event.preventDefault(); onSubmit() }}>
    {formError ? <div className="mb-5 border-l-[3px] border-[var(--color-signal)] bg-[var(--color-soft-mist)] px-4 py-3.5 text-sm" role="alert">{formError}</div> : null}
    <div className="border-y border-[var(--color-obsidian)] md:grid md:grid-cols-[minmax(0,1.2fr)_minmax(360px,0.8fr)]">
      <ProductImageField images={images} representativeIndex={representativeIndex} error={imageError} disabled={isInputLocked} onAdd={onImagesAdd} onRemove={onImageRemove} onSelectRepresentative={onRepresentativeSelect} />
      <section className="py-7 md:py-9 md:pl-12" aria-labelledby="product-info-heading">
        <h2 id="product-info-heading" className="mb-5 text-xl font-semibold">기본 정보</h2>
        <div className="mb-8"><div className="mb-2.5 flex items-baseline justify-between gap-4"><label className="font-medium" htmlFor="product-name">상품명 <span className="text-[var(--color-signal)]" aria-hidden="true">*</span></label><span className="text-xs text-[var(--color-steel)]">{name.length} / 100</span></div>
          <input id="product-name" className={`h-[52px] w-full border bg-white px-4 outline-none transition-colors focus:border-[var(--color-obsidian)] ${fieldErrors.name ? 'border-[var(--color-signal)]' : 'border-[var(--color-concrete-gray)]'}`} type="text" value={name} maxLength={100} required disabled={isInputLocked} aria-invalid={Boolean(fieldErrors.name)} aria-describedby={fieldErrors.name ? 'product-name-error' : undefined} onChange={(event) => onNameChange(event.currentTarget.value)} />
          {fieldErrors.name ? <p id="product-name-error" className="mt-2 text-sm text-[var(--color-signal)]" role="alert">{fieldErrors.name}</p> : null}
        </div>
        <div><div className="mb-2.5 flex items-baseline justify-between gap-4"><label className="font-medium" htmlFor="product-description">상품 설명</label><span className="text-xs text-[var(--color-steel)]">{description.length} / 3000</span></div>
          <textarea id="product-description" className={`min-h-44 w-full resize-y border bg-white px-4 py-3.5 outline-none transition-colors focus:border-[var(--color-obsidian)] md:min-h-56 ${fieldErrors.description ? 'border-[var(--color-signal)]' : 'border-[var(--color-concrete-gray)]'}`} value={description} maxLength={3000} disabled={isInputLocked} aria-invalid={Boolean(fieldErrors.description)} aria-describedby={fieldErrors.description ? 'product-description-error' : undefined} onChange={(event) => onDescriptionChange(event.currentTarget.value)} />
          {fieldErrors.description ? <p id="product-description-error" className="mt-2 text-sm text-[var(--color-signal)]" role="alert">{fieldErrors.description}</p> : null}
        </div>
      </section>
    </div>
    <div className="mt-7 flex items-center justify-end gap-3 max-md:fixed max-md:right-0 max-md:bottom-0 max-md:left-0 max-md:z-10 max-md:border-t max-md:border-[var(--color-concrete-gray)] max-md:bg-white max-md:px-5 max-md:pt-3 max-md:pb-[max(12px,env(safe-area-inset-bottom))]">
      <span className="mr-auto text-sm text-[var(--color-steel)]" aria-live="polite">{SUBMISSION_MESSAGES[submissionStage]}</span>
      <button className="min-h-12 rounded-full border border-[var(--color-concrete-gray)] bg-white px-7 font-medium hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3 disabled:cursor-not-allowed disabled:text-[var(--color-faint-gray)]" type="button" disabled={isSubmitting} onClick={onCancel}>취소</button>
      <button className="min-h-12 min-w-36 rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-7 font-medium text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3 disabled:cursor-not-allowed disabled:border-[var(--color-faint-gray)] disabled:bg-[var(--color-soft-mist)] disabled:text-[var(--color-faint-gray)] max-md:flex-1" type="submit" disabled={isSubmitting || submissionStage === 'blocked'}>{isSubmitting ? '등록 중...' : isRetryPending ? '이미지 등록 재시도' : '상품 등록'}</button>
    </div>
  </form>
}
