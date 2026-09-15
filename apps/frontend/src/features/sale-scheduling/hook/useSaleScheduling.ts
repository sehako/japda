import { useCallback, useEffect, useRef, useState } from 'react'

import { createSale, fetchReadyProducts } from '../api/saleSchedulingApi.ts'
import type { ReadyProductPage } from '../api/saleSchedulingApi.ts'
import { getRegistrationWindow, mapApiErrorToSaleErrors, validateSaleScheduling } from '../model/saleScheduling.ts'
import type { CreateSaleResponse, ProductSort, ReadyProduct, RegistrationWindow, SaleSchedulingFieldErrors } from '../model/saleScheduling.ts'
import { apiBaseUrl } from '../../../shared/config/env.ts'
import { ApiError } from '../../../shared/api/apiClient.ts'

interface StoredProductPage extends ReadyProductPage { cursor: string | null }
export interface CreatedSaleSummary extends CreateSaleResponse { product: ReadyProduct }
export type ProductListStatus = 'loading' | 'ready' | 'loading-next' | 'changing-sort' | 'error' | 'next-error'

export function useSaleScheduling(initialProduct?: ReadyProduct) {
  const [pages, setPages] = useState<StoredProductPage[]>([])
  const [pageIndex, setPageIndex] = useState(0)
  const [productSort, setProductSort] = useState<ProductSort>('latest')
  const [pendingSort, setPendingSort] = useState<ProductSort | null>(null)
  const [listStatus, setListStatus] = useState<ProductListStatus>('loading')
  const [listError, setListError] = useState<string | null>(null)
  const [selectedProduct, setSelectedProduct] = useState<ReadyProduct | null>(initialProduct ?? null)
  const [isPreselectedProduct, setPreselectedProduct] = useState(Boolean(initialProduct))
  const [windowState, setWindowState] = useState<RegistrationWindow>(() => getRegistrationWindow())
  const [saleDate, setSaleDate] = useState(() => getRegistrationWindow().saleDate ?? '')
  const [price, setPrice] = useState('')
  const [quantity, setQuantity] = useState('')
  const [fieldErrors, setFieldErrors] = useState<SaleSchedulingFieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [authError, setAuthError] = useState<'unauthenticated' | 'seller-link-required' | null>(null)
  const [refreshProductsRequired, setRefreshProductsRequired] = useState(false)
  const [isSubmitting, setSubmitting] = useState(false)
  const [createdSale, setCreatedSale] = useState<CreatedSaleSummary | null>(null)
  const listAbortRef = useRef<AbortController | null>(null)
  const submitAbortRef = useRef<AbortController | null>(null)
  const submittingRef = useRef(false)

  const loadFirstPage = useCallback(async (sort: ProductSort, isSortChange = false) => {
    listAbortRef.current?.abort()
    const controller = new AbortController()
    listAbortRef.current = controller
    setListStatus(isSortChange ? 'changing-sort' : 'loading')
    setListError(null)
    try {
      const page = await fetchReadyProducts(sort, null, controller.signal, { baseUrl: apiBaseUrl })
      setPages([{ cursor: null, ...page }])
      setPageIndex(0)
      setProductSort(sort)
      setPendingSort(null)
      setListStatus('ready')
      return true
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return false
      if (error instanceof ApiError && error.code === 'AUTH_UNAUTHENTICATED') setAuthError('unauthenticated')
      else if (error instanceof ApiError && error.code === 'AUTH_SELLER_LINK_REQUIRED') setAuthError('seller-link-required')
      setPendingSort(isSortChange ? sort : null)
      setListStatus('error')
      setListError(error instanceof ApiError && (error.code === 'AUTH_UNAUTHENTICATED' || error.code === 'AUTH_SELLER_LINK_REQUIRED')
        ? mapApiErrorToSaleErrors(error).formError : '상품 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')
      return false
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void loadFirstPage('latest')) }, [loadFirstPage])
  useEffect(() => {
    const update = () => {
      const next = getRegistrationWindow()
      setWindowState(next)
      setSaleDate(next.saleDate ?? '')
    }
    const timer = window.setInterval(update, 30_000)
    return () => window.clearInterval(timer)
  }, [])
  useEffect(() => () => { listAbortRef.current?.abort(); submitAbortRef.current?.abort() }, [])

  const changeSort = useCallback((sort: ProductSort) => {
    if (sort === productSort || listStatus === 'changing-sort') return
    setPendingSort(sort)
    void loadFirstPage(sort, true)
  }, [listStatus, loadFirstPage, productSort])

  const goPrevious = useCallback(() => setPageIndex((current) => Math.max(0, current - 1)), [])
  const goNext = useCallback(async () => {
    if (listStatus !== 'ready' && listStatus !== 'next-error') return
    if (pages[pageIndex + 1]) { setPageIndex(pageIndex + 1); return }
    const current = pages[pageIndex]
    if (!current?.nextCursor) return
    const controller = new AbortController()
    listAbortRef.current = controller
    setListStatus('loading-next')
    setListError(null)
    try {
      const result = await fetchReadyProducts(productSort, current.nextCursor, controller.signal, { baseUrl: apiBaseUrl })
      setPages((stored) => [...stored.slice(0, pageIndex + 1), { cursor: current.nextCursor, ...result }])
      setPageIndex(pageIndex + 1)
      setListStatus('ready')
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      if (error instanceof ApiError && error.code === 'AUTH_UNAUTHENTICATED') setAuthError('unauthenticated')
      else if (error instanceof ApiError && error.code === 'AUTH_SELLER_LINK_REQUIRED') setAuthError('seller-link-required')
      setListStatus('next-error')
      setListError(error instanceof ApiError && (error.code === 'AUTH_UNAUTHENTICATED' || error.code === 'AUTH_SELLER_LINK_REQUIRED')
        ? mapApiErrorToSaleErrors(error).formError : '다음 상품 페이지를 불러오지 못했습니다. 다시 시도해 주세요.')
    }
  }, [listStatus, pageIndex, pages, productSort])

  const toggleProduct = useCallback((product: ReadyProduct) => {
    if (submittingRef.current) return
    setSelectedProduct((current) => current?.id === product.id ? null : product)
    setPreselectedProduct(false)
    setFieldErrors((current) => ({ ...current, productId: undefined }))
  }, [])

  const refreshProducts = useCallback(() => {
    setRefreshProductsRequired(false)
    void loadFirstPage(productSort)
  }, [loadFirstPage, productSort])

  const submit = useCallback(async () => {
    if (submittingRef.current || !windowState.isOpen) return false
    const validation = validateSaleScheduling({ productId: selectedProduct?.id ?? null, saleDate, price, quantity }, windowState.saleDate)
    if (!validation.valid) { setFieldErrors(validation.errors); setFormError(null); return false }
    submittingRef.current = true
    setSubmitting(true)
    setFieldErrors({})
    setFormError(null)
    const controller = new AbortController()
    submitAbortRef.current = controller
    try {
      const sale = await createSale(validation.value, controller.signal, { baseUrl: apiBaseUrl })
      setCreatedSale({ ...sale, product: selectedProduct as ReadyProduct })
      return true
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return false
      const mapped = mapApiErrorToSaleErrors(error)
      if (error instanceof ApiError && error.code === 'AUTH_UNAUTHENTICATED') setAuthError('unauthenticated')
      else if (error instanceof ApiError && error.code === 'AUTH_SELLER_LINK_REQUIRED') setAuthError('seller-link-required')
      setFieldErrors(mapped.fieldErrors)
      setFormError(error instanceof ApiError && error.isNetworkError ? '등록 결과를 확인할 수 없습니다. 다시 요청하면 이미 등록된 일정으로 처리될 수 있습니다.' : mapped.formError)
      setRefreshProductsRequired(mapped.refreshProducts)
      if ((error as { code?: string }).code === 'SALE_REGISTRATION_CLOSED') {
        const next = getRegistrationWindow(); setWindowState(next); setSaleDate(next.saleDate ?? '')
      }
      return false
    } finally {
      submittingRef.current = false
      setSubmitting(false)
      submitAbortRef.current = null
    }
  }, [price, quantity, saleDate, selectedProduct, windowState])

  const currentPage = pages[pageIndex]
  return {
    currentPage, pageIndex, hasVisitedNext: Boolean(pages[pageIndex + 1]), productSort, pendingSort, listStatus, listError, selectedProduct, isPreselectedProduct, windowState,
    saleDate, price, quantity, fieldErrors, formError, authError, refreshProductsRequired, isSubmitting, createdSale,
    changeSort, goPrevious, goNext, toggleProduct, refreshProducts, retryList: () => listStatus === 'next-error' ? void goNext() : void loadFirstPage(pendingSort ?? productSort, pendingSort !== null),
    changeSaleDate: setSaleDate,
    changePrice: (value: string) => { setPrice(value); setFieldErrors((current) => ({ ...current, price: undefined })) },
    changeQuantity: (value: string) => { setQuantity(value); setFieldErrors((current) => ({ ...current, quantity: undefined })) },
    submit,
  }
}
