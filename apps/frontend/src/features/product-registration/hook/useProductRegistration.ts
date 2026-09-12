import { useCallback, useEffect, useRef, useState } from 'react'

import { createProduct, registerProductImages } from '../api/productRegistrationApi.ts'
import {
  adjustRepresentativeIndexAfterRemoval,
  mapApiErrorToRegistrationErrors,
  validateProductRegistration,
} from '../model/productRegistration.ts'
import type {
  ProductRegistrationFieldErrors,
  SelectedProductImage,
  SubmissionStage,
} from '../model/productRegistration.ts'
import { apiBaseUrl, sellerIdConfig } from '../../../shared/config/env.ts'

export interface ProductRegistrationResult {
  name: string
  description: string
  images: SelectedProductImage[]
  representativeIndex: number | null
  fieldErrors: ProductRegistrationFieldErrors
  formError: string | null
  submissionStage: SubmissionStage
  createdProductId: number | null
  registeredProduct: { id: number; name: string } | null
  isInputLocked: boolean
  isSubmitting: boolean
  isSubmissionBlocked: boolean
  changeName: (value: string) => void
  changeDescription: (value: string) => void
  addImages: (files: FileList | File[]) => void
  removeImage: (index: number) => void
  selectRepresentative: (index: number) => void
  submit: () => Promise<boolean>
  cancel: () => boolean
  startNew: () => void
}

const INITIAL_STAGE: SubmissionStage = 'input'

export function useProductRegistration(): ProductRegistrationResult {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [images, setImages] = useState<SelectedProductImage[]>([])
  const imagesRef = useRef(images)
  const [representativeIndex, setRepresentativeIndex] = useState<number | null>(null)
  const [createdProductId, setCreatedProductId] = useState<number | null>(null)
  const [registeredProduct, setRegisteredProduct] = useState<{ id: number; name: string } | null>(null)
  const [submissionStage, setSubmissionStage] = useState<SubmissionStage>(INITIAL_STAGE)
  const [fieldErrors, setFieldErrors] = useState<ProductRegistrationFieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [isSubmissionBlocked, setSubmissionBlocked] = useState(false)
  const submittingRef = useRef(false)
  const abortControllerRef = useRef<AbortController | null>(null)

  const isSubmitting = submissionStage === 'creating-product' || submissionStage === 'registering-images'
  const isInputLocked = isSubmitting || createdProductId !== null

  useEffect(() => {
    imagesRef.current = images
  }, [images])

  useEffect(() => () => {
    abortControllerRef.current?.abort()
    for (const image of imagesRef.current) URL.revokeObjectURL(image.previewUrl)
  }, [])

  useEffect(() => {
    if (submissionStage !== 'retry' && submissionStage !== 'blocked') return undefined
    const warnBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', warnBeforeUnload)
    return () => window.removeEventListener('beforeunload', warnBeforeUnload)
  }, [submissionStage])

  const changeName = useCallback((value: string) => {
    if (submittingRef.current || createdProductId !== null) return
    setName(value)
    setFieldErrors((current) => ({ ...current, name: undefined }))
  }, [createdProductId])

  const changeDescription = useCallback((value: string) => {
    if (submittingRef.current || createdProductId !== null) return
    setDescription(value)
    setFieldErrors((current) => ({ ...current, description: undefined }))
  }, [createdProductId])

  const addImages = useCallback((files: FileList | File[]) => {
    if (submittingRef.current || createdProductId !== null) return
    const additions = Array.from(files, (file) => ({ file, previewUrl: URL.createObjectURL(file) }))
    setImages((current) => {
      if (current.length === 0 && additions.length > 0) setRepresentativeIndex(0)
      return [...current, ...additions]
    })
    setFieldErrors((current) => ({ ...current, files: undefined, representativeIndex: undefined }))
  }, [createdProductId])

  const removeImage = useCallback((index: number) => {
    if (submittingRef.current || createdProductId !== null) return
    setImages((current) => {
      const removed = current[index]
      if (!removed) return current
      URL.revokeObjectURL(removed.previewUrl)
      setRepresentativeIndex((selected) => adjustRepresentativeIndexAfterRemoval(selected, index, current.length))
      return current.filter((_, currentIndex) => currentIndex !== index)
    })
  }, [createdProductId])

  const selectRepresentative = useCallback((index: number) => {
    if (submittingRef.current || createdProductId !== null || !images[index]) return
    setRepresentativeIndex(index)
    setFieldErrors((current) => ({ ...current, representativeIndex: undefined }))
  }, [createdProductId, images])

  const reset = useCallback(() => {
    abortControllerRef.current?.abort()
    setImages((current) => {
      for (const image of current) URL.revokeObjectURL(image.previewUrl)
      return []
    })
    setName('')
    setDescription('')
    setRepresentativeIndex(null)
    setCreatedProductId(null)
    setRegisteredProduct(null)
    setSubmissionStage(INITIAL_STAGE)
    setFieldErrors({})
    setFormError(null)
    setSubmissionBlocked(false)
    submittingRef.current = false
  }, [])

  const submit = useCallback(async (): Promise<boolean> => {
    if (submittingRef.current || isSubmissionBlocked || submissionStage === 'success') return false
    const validation = validateProductRegistration({
      name,
      description,
      files: images.map((image) => image.file),
      representativeIndex,
    })
    if (!validation.valid) {
      setFieldErrors(validation.errors)
      setFormError(null)
      return false
    }
    if (!sellerIdConfig.valid) {
      setFormError(sellerIdConfig.error)
      return false
    }

    submittingRef.current = true
    setFieldErrors({})
    setFormError(null)
    const abortController = new AbortController()
    abortControllerRef.current = abortController
    let productId = createdProductId
    try {
      if (productId === null) {
        setSubmissionStage('creating-product')
        const product = await createProduct(
          { name: validation.value.name, description: validation.value.description },
          sellerIdConfig.value,
          abortController.signal,
          { baseUrl: apiBaseUrl },
        )
        productId = product.id
        setCreatedProductId(productId)
        setRegisteredProduct({ id: product.id, name: product.name })
      }
      setSubmissionStage('registering-images')
      await registerProductImages(
        productId,
        validation.value.files,
        validation.value.representativeIndex,
        sellerIdConfig.value,
        abortController.signal,
        { baseUrl: apiBaseUrl },
      )
      setSubmissionStage('success')
      return true
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return false
      const mapped = mapApiErrorToRegistrationErrors(error)
      setFieldErrors(mapped.fieldErrors)
      setFormError(mapped.formError)
      setSubmissionBlocked(mapped.blocksSubmission)
      setSubmissionStage(productId === null ? 'input' : mapped.blocksSubmission ? 'blocked' : 'retry')
      return false
    } finally {
      submittingRef.current = false
      abortControllerRef.current = null
    }
  }, [createdProductId, description, images, isSubmissionBlocked, name, representativeIndex, submissionStage])

  const cancel = useCallback((): boolean => {
    if (isSubmitting) return false
    const hasInput = name.length > 0 || description.length > 0 || images.length > 0
    if (hasInput) {
      const message = createdProductId === null
        ? '입력한 내용을 지우고 상품 등록을 취소하시겠습니까?'
        : '기본 정보만 저장된 DRAFT 상품이 남습니다. 현재 등록을 포기하시겠습니까?'
      if (!window.confirm(message)) return false
    }
    reset()
    return true
  }, [createdProductId, description.length, images.length, isSubmitting, name.length, reset])

  return {
    name,
    description,
    images,
    representativeIndex,
    fieldErrors,
    formError,
    submissionStage,
    createdProductId,
    registeredProduct,
    isInputLocked,
    isSubmitting,
    isSubmissionBlocked,
    changeName,
    changeDescription,
    addImages,
    removeImage,
    selectRepresentative,
    submit,
    cancel,
    startNew: reset,
  }
}
