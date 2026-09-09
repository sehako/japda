import { useEffect, useRef, useState } from 'react'
import {
  ProductRegistrationApiError,
  createProduct,
  createSale,
  uploadProductImages,
} from '../api/productRegistrationApi'
import {
  INITIAL_PRODUCT_REGISTRATION_DRAFT,
  INITIAL_PRODUCT_REGISTRATION_SUBMISSION_STATE,
  type ProductRegistrationDraft,
  type ProductRegistrationErrors,
  type ProductRegistrationFailureStage,
  type ProductRegistrationField,
  type ProductRegistrationImage,
  type ProductRegistrationStage,
} from '../model/productRegistration'
import {
  toCreateProductRequest,
  toCreateSaleRequest,
  toUploadProductImagesRequest,
} from '../model/productRegistrationMapper'
import {
  validateProductRegistration,
  validateProductRegistrationField,
  validateProductRegistrationImages,
} from '../model/productRegistrationValidation'

type ProductRegistrationTextField = Exclude<
  ProductRegistrationField,
  'images'
>

const SERVER_FIELD_MAP: Record<string, ProductRegistrationField | undefined> = {
  name: 'name',
  description: 'description',
  files: 'images',
  representativeIndex: 'images',
  price: 'price',
  quantity: 'quantity',
  startsAt: 'saleStartsAt',
  endsAt: 'saleEndsAt',
}

function createImageId() {
  return crypto.randomUUID()
}

function canEditField(
  field: ProductRegistrationField,
  completedStage: ProductRegistrationStage,
) {
  if (completedStage === 'none') {
    return true
  }

  if (completedStage === 'product') {
    return field !== 'name' && field !== 'description'
  }

  if (completedStage === 'images') {
    return field === 'price' || field === 'quantity' || field.startsWith('sale')
  }

  return false
}

function serverErrorMessage(value: string | string[]) {
  return Array.isArray(value) ? value.join(' ') : value
}

export function useProductRegistration() {
  const [draft, setDraft] = useState<ProductRegistrationDraft>(
    INITIAL_PRODUCT_REGISTRATION_DRAFT,
  )
  const [errors, setErrors] = useState<ProductRegistrationErrors>({})
  const [hasSubmitted, setHasSubmitted] = useState(false)
  const [submission, setSubmission] = useState(
    INITIAL_PRODUCT_REGISTRATION_SUBMISSION_STATE,
  )
  const imagesRef = useRef<ProductRegistrationImage[]>([])
  const pendingRef = useRef(false)
  const validation = validateProductRegistration(draft)

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

  const clearFailureMessage = () => {
    setSubmission((current) => ({
      ...current,
      formError: null,
    }))
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
    clearFailureMessage()
    updateErrors(nextDraft, relatedFields)
  }

  const addImages = (files: FileList | null) => {
    if (files === null || files.length === 0) {
      return
    }

    const selectedFiles = Array.from(files)
    const imageError = validateProductRegistrationImages([
      ...draft.images,
      ...selectedFiles.map((file) => ({ file })),
    ])

    if (imageError !== undefined) {
      setHasSubmitted(true)
      setErrors((current) => ({ ...current, images: imageError }))
      return
    }

    const addedImages = selectedFiles.map((file) => ({
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
    clearFailureMessage()
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
    clearFailureMessage()
    updateErrors(nextDraft, ['images'])
  }

  const setPrimaryImage = (imageId: string) => {
    if (!draft.images.some((image) => image.id === imageId)) {
      return
    }

    const nextDraft = { ...draft, primaryImageId: imageId }
    setDraft(nextDraft)
    clearFailureMessage()
    updateErrors(nextDraft, ['images'])
  }

  const applyRequestError = (
    error: unknown,
    completedStage: ProductRegistrationStage,
  ) => {
    const nextErrors: ProductRegistrationErrors = {}
    const formMessages: string[] = []

    if (error instanceof ProductRegistrationApiError) {
      const serverErrors = error.problem?.errors ?? {}

      for (const [serverField, value] of Object.entries(serverErrors)) {
        const field = SERVER_FIELD_MAP[serverField]
        const message = serverErrorMessage(value)

        if (field !== undefined && canEditField(field, completedStage)) {
          nextErrors[field] = message
        } else {
          formMessages.push(message)
        }
      }

      if (Object.keys(serverErrors).length === 0 || formMessages.length > 0) {
        formMessages.unshift(error.message)
      }
    } else {
      formMessages.push('상품 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.')
    }

    setErrors(nextErrors)
    return {
      firstErrorField:
        (Object.keys(nextErrors)[0] as ProductRegistrationField | undefined) ??
        null,
      formError: formMessages.join(' '),
    }
  }

  const submit = async (): Promise<ProductRegistrationField | null> => {
    if (pendingRef.current) {
      return null
    }

    const result = validateProductRegistration(draft)
    setHasSubmitted(true)
    setErrors(result.errors)

    if (result.firstErrorField !== null) {
      return result.firstErrorField
    }

    pendingRef.current = true
    setSubmission((current) => ({
      ...current,
      isPending: true,
      failureStage: null,
      formError: null,
    }))

    let activeProductId = submission.productId
    let activeStage = submission.completedStage
    let failureStage: ProductRegistrationFailureStage = 'product'

    try {
      if (activeStage === 'none') {
        failureStage = 'product'
        const product = await createProduct(toCreateProductRequest(draft))
        activeProductId = product.id
        activeStage = 'product'
        setSubmission((current) => ({
          ...current,
          productId: product.id,
          completedStage: 'product',
        }))
      }

      if (activeStage === 'product') {
        failureStage = 'images'
        await uploadProductImages(
          activeProductId!,
          toUploadProductImagesRequest(draft),
        )
        activeStage = 'images'
        setSubmission((current) => ({
          ...current,
          completedStage: 'images',
        }))
      }

      if (activeStage === 'images') {
        failureStage = 'sale'
        await createSale(activeProductId!, toCreateSaleRequest(draft))
      }

      setErrors({})
      setSubmission({
        completedStage: 'sale',
        productId: activeProductId,
        isPending: false,
        failureStage: null,
        isComplete: true,
        formError: null,
      })
      return null
    } catch (error) {
      const requestError = applyRequestError(error, activeStage)
      setSubmission({
        completedStage: activeStage,
        productId: activeProductId,
        isPending: false,
        failureStage,
        isComplete: false,
        formError: requestError.formError,
      })
      return requestError.firstErrorField
    } finally {
      pendingRef.current = false
    }
  }

  const reset = () => {
    for (const image of imagesRef.current) {
      URL.revokeObjectURL(image.previewUrl)
    }
    imagesRef.current = []
    pendingRef.current = false
    setDraft(INITIAL_PRODUCT_REGISTRATION_DRAFT)
    setErrors({})
    setHasSubmitted(false)
    setSubmission(INITIAL_PRODUCT_REGISTRATION_SUBMISSION_STATE)
  }

  return {
    draft,
    errors,
    validationErrors: validation.errors,
    isDraftValid: validation.firstErrorField === null,
    submission,
    updateField,
    addImages,
    removeImage,
    setPrimaryImage,
    submit,
    reset,
  }
}
