import type { Ref } from 'react'
import type { ProductRegistrationErrors } from '../model/productRegistration'

type ProductInformationSectionProps = {
  name: string
  description: string
  errors: ProductRegistrationErrors
  nameRef: Ref<HTMLInputElement>
  descriptionRef: Ref<HTMLTextAreaElement>
  disabled: boolean
  onNameChange: (value: string) => void
  onDescriptionChange: (value: string) => void
}

const inputClassName =
  'mt-2 w-full border border-concrete-gray bg-paper-white px-4 py-3 text-body text-obsidian outline-none transition-colors placeholder:text-faint-gray focus:border-obsidian'

export function ProductInformationSection({
  name,
  description,
  errors,
  nameRef,
  descriptionRef,
  disabled,
  onNameChange,
  onDescriptionChange,
}: ProductInformationSectionProps) {
  return (
    <section
      aria-labelledby="product-information-title"
      className="border border-concrete-gray bg-paper-white p-5 sm:p-7"
    >
      <div className="border-b border-concrete-gray pb-5">
        <p className="text-caption font-medium text-steel">01</p>
        <h2
          id="product-information-title"
          className="mt-1 text-subheading font-medium text-obsidian"
        >
          상품 정보
        </h2>
      </div>

      <div className="mt-6 space-y-6">
        <div>
          <label htmlFor="product-name" className="text-body-sm font-medium">
            상품명
          </label>
          <input
            ref={nameRef}
            id="product-name"
            type="text"
            value={name}
            disabled={disabled}
            aria-describedby="product-name-error"
            aria-invalid={errors.name !== undefined}
            className={inputClassName}
            onChange={(event) => onNameChange(event.target.value)}
          />
          {errors.name !== undefined ? (
            <p id="product-name-error" className="mt-2 text-caption text-signal">
              {errors.name}
            </p>
          ) : null}
        </div>

        <div>
          <label
            htmlFor="product-description"
            className="text-body-sm font-medium"
          >
            상품 설명
          </label>
          <textarea
            ref={descriptionRef}
            id="product-description"
            rows={6}
            value={description}
            disabled={disabled}
            placeholder="소재, 구성, 특징 등 상품 정보를 입력해 주세요."
            aria-describedby="product-description-help product-description-error"
            aria-invalid={errors.description !== undefined}
            className={`${inputClassName} resize-y`}
            onChange={(event) => onDescriptionChange(event.target.value)}
          />
          <p
            id="product-description-help"
            className="mt-2 text-caption text-steel"
          >
            최대 5,000자까지 입력할 수 있습니다.
          </p>
          {errors.description !== undefined ? (
            <p
              id="product-description-error"
              className="mt-2 text-caption text-signal"
            >
              {errors.description}
            </p>
          ) : null}
        </div>
      </div>
    </section>
  )
}
