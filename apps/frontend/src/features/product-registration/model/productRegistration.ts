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
