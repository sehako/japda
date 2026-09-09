import type { Ref } from 'react'
import type {
  ProductRegistrationErrors,
  ProductRegistrationImage,
} from '../model/productRegistration'

type ProductImageSectionProps = {
  images: ProductRegistrationImage[]
  primaryImageId: string | null
  errors: ProductRegistrationErrors
  inputRef: Ref<HTMLInputElement>
  onImagesAdd: (files: FileList | null) => void
  onPrimaryImageChange: (imageId: string) => void
  onImageRemove: (imageId: string) => void
}

export function ProductImageSection({
  images,
  primaryImageId,
  errors,
  inputRef,
  onImagesAdd,
  onPrimaryImageChange,
  onImageRemove,
}: ProductImageSectionProps) {
  return (
    <section
      aria-labelledby="product-image-title"
      className="border border-concrete-gray bg-paper-white p-5 sm:p-7"
    >
      <div className="border-b border-concrete-gray pb-5">
        <p className="text-caption font-medium text-steel">02</p>
        <h2
          id="product-image-title"
          className="mt-1 text-subheading font-medium text-obsidian"
        >
          상품 이미지
        </h2>
      </div>

      <div className="mt-6">
        <label htmlFor="product-images" className="text-body-sm font-medium">
          이미지 선택
        </label>
        <input
          ref={inputRef}
          id="product-images"
          type="file"
          accept="image/*"
          multiple
          aria-describedby="product-images-help product-images-error"
          aria-invalid={errors.images !== undefined}
          className="mt-2 block w-full border border-dashed border-concrete-gray bg-soft-mist px-4 py-6 text-body-sm text-steel file:mr-4 file:rounded-button file:border-0 file:bg-obsidian file:px-5 file:py-2.5 file:text-body-sm file:font-medium file:text-paper-white hover:file:bg-black"
          onChange={(event) => {
            onImagesAdd(event.target.files)
            event.target.value = ''
          }}
        />
        <p id="product-images-help" className="mt-2 text-caption text-steel">
          여러 장을 선택할 수 있습니다. 첫 이미지가 대표로 지정됩니다.
        </p>
        {errors.images !== undefined ? (
          <p id="product-images-error" className="mt-2 text-caption text-signal">
            {errors.images}
          </p>
        ) : null}
      </div>

      {images.length > 0 ? (
        <ul className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2">
          {images.map((image, index) => {
            const isPrimary = image.id === primaryImageId

            return (
              <li key={image.id} className="border border-concrete-gray">
                <div className="relative aspect-square overflow-hidden bg-soft-mist">
                  <img
                    src={image.previewUrl}
                    alt={`${image.file.name} 미리보기`}
                    className="h-full w-full object-cover"
                  />
                  {isPrimary ? (
                    <span className="absolute top-3 left-3 rounded-button bg-obsidian px-3 py-1 text-caption font-medium text-paper-white">
                      대표 이미지
                    </span>
                  ) : null}
                </div>
                <div className="p-4">
                  <p className="truncate text-body-sm font-medium text-obsidian">
                    {index + 1}. {image.file.name}
                  </p>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <button
                      type="button"
                      disabled={isPrimary}
                      className="border border-obsidian px-3 py-2 text-caption font-medium text-obsidian disabled:cursor-not-allowed disabled:border-concrete-gray disabled:text-faint-gray"
                      onClick={() => onPrimaryImageChange(image.id)}
                    >
                      {isPrimary ? '대표 지정됨' : '대표로 지정'}
                    </button>
                    <button
                      type="button"
                      className="border border-concrete-gray px-3 py-2 text-caption font-medium text-steel hover:border-signal hover:text-signal"
                      aria-label={`${image.file.name} 이미지 삭제`}
                      onClick={() => onImageRemove(image.id)}
                    >
                      삭제
                    </button>
                  </div>
                </div>
              </li>
            )
          })}
        </ul>
      ) : (
        <div className="mt-6 flex aspect-[2/1] items-center justify-center border border-concrete-gray bg-soft-mist px-6 text-center text-body-sm text-steel">
          선택한 이미지가 없습니다.
        </div>
      )}
    </section>
  )
}
