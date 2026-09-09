import type { Ref } from 'react'
import type { ProductRegistrationErrors } from '../model/productRegistration'

type ProductSaleSectionProps = {
  price: string
  saleStartsAt: string
  saleEndsAt: string
  quantity: string
  errors: ProductRegistrationErrors
  priceRef: Ref<HTMLInputElement>
  saleStartsAtRef: Ref<HTMLInputElement>
  saleEndsAtRef: Ref<HTMLInputElement>
  quantityRef: Ref<HTMLInputElement>
  disabled: boolean
  onPriceChange: (value: string) => void
  onSaleStartsAtChange: (value: string) => void
  onSaleEndsAtChange: (value: string) => void
  onQuantityChange: (value: string) => void
}

const inputClassName =
  'mt-2 w-full border border-concrete-gray bg-paper-white px-4 py-3 text-body text-obsidian outline-none transition-colors placeholder:text-faint-gray focus:border-obsidian'

type FieldProps = {
  id: string
  label: string
  value: string
  type: 'text' | 'datetime-local'
  error?: string
  helper?: string
  inputRef: Ref<HTMLInputElement>
  inputMode?: 'numeric'
  placeholder?: string
  onChange: (value: string) => void
  disabled: boolean
}

function SaleField({
  id,
  label,
  value,
  type,
  error,
  helper,
  inputRef,
  inputMode,
  placeholder,
  onChange,
  disabled,
}: FieldProps) {
  return (
    <div>
      <label htmlFor={id} className="text-body-sm font-medium">
        {label}
      </label>
      <input
        ref={inputRef}
        id={id}
        type={type}
        inputMode={inputMode}
        value={value}
        disabled={disabled}
        placeholder={placeholder}
        aria-describedby={
          helper === undefined ? `${id}-error` : `${id}-help ${id}-error`
        }
        aria-invalid={error !== undefined}
        className={inputClassName}
        onChange={(event) => onChange(event.target.value)}
      />
      {helper === undefined ? null : (
        <p id={`${id}-help`} className="mt-2 text-caption text-steel">
          {helper}
        </p>
      )}
      {error !== undefined ? (
        <p id={`${id}-error`} className="mt-2 text-caption text-signal">
          {error}
        </p>
      ) : null}
    </div>
  )
}

export function ProductSaleSection({
  price,
  saleStartsAt,
  saleEndsAt,
  quantity,
  errors,
  priceRef,
  saleStartsAtRef,
  saleEndsAtRef,
  quantityRef,
  disabled,
  onPriceChange,
  onSaleStartsAtChange,
  onSaleEndsAtChange,
  onQuantityChange,
}: ProductSaleSectionProps) {
  return (
    <section
      aria-labelledby="product-sale-title"
      className="border border-concrete-gray bg-paper-white p-5 sm:p-7"
    >
      <div className="border-b border-concrete-gray pb-5">
        <p className="text-caption font-medium text-steel">03</p>
        <h2
          id="product-sale-title"
          className="mt-1 text-subheading font-medium text-obsidian"
        >
          판매 일정 및 수량
        </h2>
      </div>

      <div className="mt-6 grid gap-6 sm:grid-cols-2">
        <SaleField
          id="product-price"
          label="판매 가격"
          type="text"
          inputMode="numeric"
          value={price}
          placeholder="예: 120000"
          helper="원 단위의 1 이상의 정수를 입력해 주세요."
          error={errors.price}
          inputRef={priceRef}
          onChange={onPriceChange}
          disabled={disabled}
        />
        <SaleField
          id="product-quantity"
          label="판매 수량"
          type="text"
          inputMode="numeric"
          value={quantity}
          placeholder="예: 100"
          error={errors.quantity}
          inputRef={quantityRef}
          onChange={onQuantityChange}
          disabled={disabled}
        />
        <SaleField
          id="sale-starts-at"
          label="판매 시작 시각"
          type="datetime-local"
          value={saleStartsAt}
          helper="판매가 열리는 현지 시각입니다."
          error={errors.saleStartsAt}
          inputRef={saleStartsAtRef}
          onChange={onSaleStartsAtChange}
          disabled={disabled}
        />
        <SaleField
          id="sale-ends-at"
          label="판매 종료 시각"
          type="datetime-local"
          value={saleEndsAt}
          helper="판매 시작 시각보다 이후여야 합니다."
          error={errors.saleEndsAt}
          inputRef={saleEndsAtRef}
          onChange={onSaleEndsAtChange}
          disabled={disabled}
        />
      </div>
    </section>
  )
}
