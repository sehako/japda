import { useRef, type FormEvent } from 'react'
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
    isValidationComplete,
    updateField,
    addImages,
    removeImage,
    setPrimaryImage,
    validate,
  } = useProductRegistration()
  const nameRef = useRef<HTMLInputElement>(null)
  const descriptionRef = useRef<HTMLTextAreaElement>(null)
  const imagesRef = useRef<HTMLInputElement>(null)
  const priceRef = useRef<HTMLInputElement>(null)
  const saleStartsAtRef = useRef<HTMLInputElement>(null)
  const saleEndsAtRef = useRef<HTMLInputElement>(null)
  const quantityRef = useRef<HTMLInputElement>(null)

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

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const firstErrorField = validate()

    if (firstErrorField !== null) {
      focusField(firstErrorField)
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div aria-live="polite" aria-atomic="true">
        {isValidationComplete ? (
          <div
            role="status"
            className="mb-6 border border-obsidian bg-obsidian px-5 py-4 text-body-sm font-medium text-paper-white"
          >
            상품 등록 UI 검증이 완료되었습니다
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
            onNameChange={(value) => updateField('name', value)}
            onDescriptionChange={(value) => updateField('description', value)}
          />
          <ProductImageSection
            images={draft.images}
            primaryImageId={draft.primaryImageId}
            errors={errors}
            inputRef={imagesRef}
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
            onPriceChange={(value) => updateField('price', value)}
            onSaleStartsAtChange={(value) => updateField('saleStartsAt', value)}
            onSaleEndsAtChange={(value) => updateField('saleEndsAt', value)}
            onQuantityChange={(value) => updateField('quantity', value)}
          />
        </div>
        <ProductRegistrationSummary draft={draft} />
      </div>
    </form>
  )
}
