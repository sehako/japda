export type ProductRegistrationImage = {
  id: string
  file: File
  previewUrl: string
}

export type ProductRegistrationDraft = {
  name: string
  description: string
  images: ProductRegistrationImage[]
  primaryImageId: string | null
  price: string
  saleStartsAt: string
  saleEndsAt: string
  quantity: string
}

export type ProductRegistrationField =
  | 'name'
  | 'description'
  | 'images'
  | 'price'
  | 'saleStartsAt'
  | 'saleEndsAt'
  | 'quantity'

export type ProductRegistrationErrors = Partial<
  Record<ProductRegistrationField, string>
>

export type CreateProductRequest = {
  name: string
  description: string
}

export type ProductResponse = {
  id: number
  sellerId: number
  name: string
  description: string
  status: string
  createdAt: string
}

export type UploadProductImagesRequest = {
  files: File[]
  representativeIndex: number
}

export type ProductImageResponse = {
  id: number
  sortOrder: number
  representative: boolean
  contentType: string
  sizeBytes: number
}

export type ProductImageUploadResponse = {
  productId: number
  status: string
  images: ProductImageResponse[]
}

export type CreateSaleRequest = {
  price: number
  quantity: number
  startsAt: string
  endsAt: string
}

export type SaleResponse = {
  id: number
  productId: number
  price: number
  initialQuantity: number
  remainingQuantity: number
  startsAt: string
  endsAt: string
  status: string
  createdAt: string
}

export type ProblemDetail = {
  title?: string
  status?: number
  detail?: string
  instance?: string
  errors?: Record<string, string | string[]>
}

export type ProductRegistrationStage = 'none' | 'product' | 'images' | 'sale'

export type ProductRegistrationFailureStage = Exclude<
  ProductRegistrationStage,
  'none'
>

export type ProductRegistrationSubmissionState = {
  completedStage: ProductRegistrationStage
  productId: number | null
  isPending: boolean
  failureStage: ProductRegistrationFailureStage | null
  isComplete: boolean
  formError: string | null
}

export const INITIAL_PRODUCT_REGISTRATION_SUBMISSION_STATE: ProductRegistrationSubmissionState =
  {
    completedStage: 'none',
    productId: null,
    isPending: false,
    failureStage: null,
    isComplete: false,
    formError: null,
  }

export const PRODUCT_REGISTRATION_FIELDS: ProductRegistrationField[] = [
  'name',
  'description',
  'images',
  'price',
  'saleStartsAt',
  'saleEndsAt',
  'quantity',
]

export const INITIAL_PRODUCT_REGISTRATION_DRAFT: ProductRegistrationDraft = {
  name: '',
  description: '',
  images: [],
  primaryImageId: null,
  price: '',
  saleStartsAt: '',
  saleEndsAt: '',
  quantity: '',
}
