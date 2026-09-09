import { useEffect, useRef, type FormEvent } from 'react'
import { useProductRegistration } from '../hook/useProductRegistration'
import type { ProductRegistrationField } from '../model/productRegistration'
import { ProductImageSection } from './ProductImageSection'
import { ProductInformationSection } from './ProductInformationSection'
import { ProductRegistrationSummary } from './ProductRegistrationSummary'
import { ProductSaleSection } from './ProductSaleSection'

export function ProductRegistrationForm() {
  const {
    draft,
    errors,
    validationErrors,
    isDraftValid,
    submission,
    updateField,
    addImages,
    removeImage,
    setPrimaryImage,
    submit,
    reset,
  } = useProductRegistration()
  const nameRef = useRef<HTMLInputElement>(null)
  const descriptionRef = useRef<HTMLTextAreaElement>(null)
  const imagesRef = useRef<HTMLInputElement>(null)
  const priceRef = useRef<HTMLInputElement>(null)
  const saleStartsAtRef = useRef<HTMLInputElement>(null)
  const saleEndsAtRef = useRef<HTMLInputElement>(null)
  const quantityRef = useRef<HTMLInputElement>(null)
  const focusTargetRef = useRef<ProductRegistrationField | null>(null)

  const focusField = (field: ProductRegistrationField) => {
    const fieldRefs: Record<ProductRegistrationField, HTMLElement | null> = {
      name: nameRef.current,
      description: descriptionRef.current,
      images: imagesRef.current,
      price: priceRef.current,
      saleStartsAt: saleStartsAtRef.current,
      saleEndsAt: saleEndsAtRef.current,
      quantity: quantityRef.current,
    }

    fieldRefs[field]?.focus()
  }

  useEffect(() => {
    if (focusTargetRef.current === null) {
      return
    }

    focusField(focusTargetRef.current)
    focusTargetRef.current = null
  }, [errors, submission.isPending])

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const firstErrorField = await submit()

    if (firstErrorField !== null) {
      focusTargetRef.current = firstErrorField
    }
  }

  const productDisabled =
    submission.isPending || submission.completedStage !== 'none'
  const imagesDisabled =
    submission.isPending ||
    submission.completedStage === 'images' ||
    submission.completedStage === 'sale'
  const saleDisabled = submission.isPending || submission.isComplete

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div aria-live="polite" aria-atomic="true">
        {submission.isComplete ? (
          <div
            role="status"
            className="mb-6 border border-obsidian bg-obsidian px-5 py-4 text-body-sm font-medium text-paper-white"
          >
            <p>상품 등록이 완료되었습니다</p>
          </div>
        ) : null}
        {submission.failureStage !== null ? (
          <div
            role="alert"
            className="mb-6 border border-signal px-5 py-4 text-body-sm text-signal"
          >
            <p>
              {submission.formError ||
                '상품 등록에 실패했습니다. 다시 시도해 주세요.'}
            </p>
            <p className="mt-1">
              새로고침하면 현재 진행 정보는 복구되지 않습니다.
            </p>
          </div>
        ) : null}
      </div>

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_360px] lg:gap-8">
        <div className="space-y-6">
          <ProductInformationSection
            name={draft.name}
            description={draft.description}
            errors={errors}
            nameRef={nameRef}
            descriptionRef={descriptionRef}
            disabled={productDisabled}
            onNameChange={(value) => updateField('name', value)}
            onDescriptionChange={(value) => updateField('description', value)}
          />
          <ProductImageSection
            images={draft.images}
            primaryImageId={draft.primaryImageId}
            errors={errors}
            inputRef={imagesRef}
            disabled={imagesDisabled}
            onImagesAdd={addImages}
            onPrimaryImageChange={setPrimaryImage}
            onImageRemove={removeImage}
          />
          <ProductSaleSection
            price={draft.price}
            saleStartsAt={draft.saleStartsAt}
            saleEndsAt={draft.saleEndsAt}
            quantity={draft.quantity}
            errors={errors}
            priceRef={priceRef}
            saleStartsAtRef={saleStartsAtRef}
            saleEndsAtRef={saleEndsAtRef}
            quantityRef={quantityRef}
            disabled={saleDisabled}
            onPriceChange={(value) => updateField('price', value)}
            onSaleStartsAtChange={(value) => updateField('saleStartsAt', value)}
            onSaleEndsAtChange={(value) => updateField('saleEndsAt', value)}
            onQuantityChange={(value) => updateField('quantity', value)}
          />
        </div>
        <ProductRegistrationSummary
          draft={draft}
          validationErrors={validationErrors}
          submission={submission}
          isDraftValid={isDraftValid}
          onReset={reset}
        />
      </div>
    </form>
  )
}
