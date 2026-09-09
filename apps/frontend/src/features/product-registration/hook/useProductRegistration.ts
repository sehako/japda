import { useEffect, useRef, useState } from 'react'
import {
  INITIAL_PRODUCT_REGISTRATION_DRAFT,
  type ProductRegistrationDraft,
  type ProductRegistrationErrors,
  type ProductRegistrationField,
  type ProductRegistrationImage,
} from '../model/productRegistration'
import {
  validateProductRegistration,
  validateProductRegistrationField,
} from '../model/productRegistrationValidation'

type ProductRegistrationTextField = Exclude<
  ProductRegistrationField,
  'images'
>

function createImageId() {
  return crypto.randomUUID()
}

export function useProductRegistration() {
  const [draft, setDraft] = useState<ProductRegistrationDraft>(
    INITIAL_PRODUCT_REGISTRATION_DRAFT,
  )
  const [errors, setErrors] = useState<ProductRegistrationErrors>({})
  const [hasSubmitted, setHasSubmitted] = useState(false)
  const [isValidationComplete, setIsValidationComplete] = useState(false)
  const imagesRef = useRef<ProductRegistrationImage[]>([])

  useEffect(() => {
    imagesRef.current = draft.images
  }, [draft.images])

  useEffect(
    () => () => {
      for (const image of imagesRef.current) {
        URL.revokeObjectURL(image.previewUrl)
      }
    },
    [],
  )

  const updateErrors = (
    nextDraft: ProductRegistrationDraft,
    fields: ProductRegistrationField[],
  ) => {
    if (!hasSubmitted) {
      return
    }

    setErrors((currentErrors) => {
      const nextErrors = { ...currentErrors }

      for (const field of fields) {
        const error = validateProductRegistrationField(nextDraft, field)

        if (error === undefined) {
          delete nextErrors[field]
        } else {
          nextErrors[field] = error
        }
      }

      return nextErrors
    })
  }

  const updateField = (
    field: ProductRegistrationTextField,
    value: string,
  ) => {
    const nextDraft = { ...draft, [field]: value }
    const relatedFields: ProductRegistrationField[] =
      field === 'saleStartsAt' || field === 'saleEndsAt'
        ? ['saleStartsAt', 'saleEndsAt']
        : [field]

    setDraft(nextDraft)
    setIsValidationComplete(false)
    updateErrors(nextDraft, relatedFields)
  }

  const addImages = (files: FileList | null) => {
    if (files === null || files.length === 0) {
      return
    }

    const addedImages = Array.from(files, (file) => ({
      id: createImageId(),
      file,
      previewUrl: URL.createObjectURL(file),
    }))
    const nextImages = [...draft.images, ...addedImages]
    const nextDraft = {
      ...draft,
      images: nextImages,
      primaryImageId: draft.primaryImageId ?? addedImages[0].id,
    }

    imagesRef.current = nextImages
    setDraft(nextDraft)
    setIsValidationComplete(false)
    updateErrors(nextDraft, ['images'])
  }

  const removeImage = (imageId: string) => {
    const removedImage = draft.images.find((image) => image.id === imageId)

    if (removedImage === undefined) {
      return
    }

    URL.revokeObjectURL(removedImage.previewUrl)
    const nextImages = draft.images.filter((image) => image.id !== imageId)
    const nextDraft = {
      ...draft,
      images: nextImages,
      primaryImageId:
        draft.primaryImageId === imageId
          ? (nextImages[0]?.id ?? null)
          : draft.primaryImageId,
    }

    imagesRef.current = nextImages
    setDraft(nextDraft)
    setIsValidationComplete(false)
    updateErrors(nextDraft, ['images'])
  }

  const setPrimaryImage = (imageId: string) => {
    if (!draft.images.some((image) => image.id === imageId)) {
      return
    }

    const nextDraft = { ...draft, primaryImageId: imageId }
    setDraft(nextDraft)
    setIsValidationComplete(false)
    updateErrors(nextDraft, ['images'])
  }

  const validate = () => {
    const result = validateProductRegistration(draft)

    setHasSubmitted(true)
    setErrors(result.errors)
    setIsValidationComplete(result.firstErrorField === null)

    return result.firstErrorField
  }

  return {
    draft,
    errors,
    isValidationComplete,
    updateField,
    addImages,
    removeImage,
    setPrimaryImage,
    validate,
  }
}
