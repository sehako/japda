export interface ProductRegistrationImage {
  file: File
  previewUrl: string
}

interface ProductImageFieldProps {
  images: ProductRegistrationImage[]
  representativeIndex: number | null
  error?: string
  disabled?: boolean
  onAdd: (files: File[]) => void
  onRemove: (index: number) => void
  onSelectRepresentative: (index: number) => void
}

const IMAGE_HELP_ID = 'product-images-help'
const IMAGE_ERROR_ID = 'product-images-error'

export function ProductImageField({ images, representativeIndex, error, disabled = false, onAdd, onRemove, onSelectRepresentative }: ProductImageFieldProps) {
  const describedBy = error ? `${IMAGE_HELP_ID} ${IMAGE_ERROR_ID}` : IMAGE_HELP_ID

  return (
    <section className="border-b border-[var(--color-concrete-gray)] py-7 md:border-r md:border-b-0 md:py-9 md:pr-12" aria-labelledby="product-images-heading">
      <div className="mb-5 flex items-baseline justify-between gap-4">
        <h2 id="product-images-heading" className="text-xl font-semibold">상품 이미지</h2>
        <span className="text-xs text-[var(--color-steel)]">{images.length} / 10</span>
      </div>
      <label className={`grid min-h-48 cursor-pointer place-items-center border border-dashed bg-[var(--color-soft-mist)] px-5 py-7 text-center transition-colors focus-within:border-[var(--color-obsidian)] hover:border-[var(--color-obsidian)] hover:bg-white md:min-h-60 md:px-9 md:py-9 ${error ? 'border-[var(--color-signal)]' : 'border-[var(--color-faint-gray)]'}`}>
        <span>
          <span className="mx-auto mb-5 grid size-10 place-items-center rounded-full border border-[var(--color-obsidian)] text-[22px] leading-none" aria-hidden="true">+</span>
          <span className="block text-base font-medium">이미지 추가</span>
          <span id={IMAGE_HELP_ID} className="mt-1 block text-xs leading-5 text-[var(--color-steel)]">JPEG, PNG, WebP · 파일당 최대 10MiB · 전체 최대 50MiB</span>
        </span>
        <input className="sr-only" type="file" accept="image/jpeg,image/png,image/webp" multiple disabled={disabled} aria-invalid={Boolean(error)} aria-describedby={describedBy} onChange={(event) => { onAdd(Array.from(event.currentTarget.files ?? [])); event.currentTarget.value = '' }} />
      </label>
      {images.length > 0 ? (
        <div className="mt-4 grid grid-cols-2 gap-3 md:grid-cols-3" aria-live="polite">
          {images.map((image, index) => {
            const isRepresentative = index === representativeIndex
            return (
              <article className={`relative aspect-square overflow-hidden bg-[var(--color-soft-mist)] ${isRepresentative ? 'border-2 border-[var(--color-obsidian)]' : 'border border-[var(--color-concrete-gray)]'}`} key={image.previewUrl}>
                <img className="block size-full object-cover" src={image.previewUrl} alt={`${image.file.name} 미리보기${isRepresentative ? ', 대표 이미지' : ''}`} />
                <span className="absolute top-2 left-2 grid h-6 min-w-6 place-items-center rounded-full bg-[rgba(17,17,17,0.82)] px-2 text-[11px] text-white" aria-hidden="true">{index + 1}</span>
                <button className="absolute top-2 right-2 size-7 cursor-pointer rounded-full border-0 bg-[rgba(17,17,17,0.82)] text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--color-obsidian)] disabled:cursor-not-allowed disabled:opacity-50" type="button" disabled={disabled} aria-label={`${image.file.name} 삭제`} onClick={() => onRemove(index)}>×</button>
                <button className="absolute bottom-2 left-2 min-h-8 cursor-pointer rounded-full border border-white bg-[rgba(17,17,17,0.82)] px-3 text-[11px] font-semibold text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--color-obsidian)] disabled:cursor-not-allowed disabled:opacity-50" type="button" disabled={disabled} aria-pressed={isRepresentative} onClick={() => onSelectRepresentative(index)}>{isRepresentative ? '대표 이미지' : '대표 이미지 지정'}</button>
              </article>
            )
          })}
        </div>
      ) : null}
      {error ? <p id={IMAGE_ERROR_ID} className="mt-3 text-sm text-[var(--color-signal)]" role="alert">{error}</p> : null}
    </section>
  )
}
