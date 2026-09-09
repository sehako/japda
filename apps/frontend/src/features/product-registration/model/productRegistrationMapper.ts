import type {
  CreateProductRequest,
  CreateSaleRequest,
  ProductRegistrationDraft,
  UploadProductImagesRequest,
} from './productRegistration'

export function toCreateProductRequest(
  draft: ProductRegistrationDraft,
): CreateProductRequest {
  return {
    name: draft.name.trim(),
    description: draft.description.trim(),
  }
}

export function toUploadProductImagesRequest(
  draft: ProductRegistrationDraft,
): UploadProductImagesRequest {
  return {
    files: draft.images.map((image) => image.file),
    representativeIndex: draft.images.findIndex(
      (image) => image.id === draft.primaryImageId,
    ),
  }
}

export function toCreateSaleRequest(
  draft: ProductRegistrationDraft,
): CreateSaleRequest {
  return {
    price: Number(draft.price),
    quantity: Number(draft.quantity),
    startsAt: new Date(draft.saleStartsAt).toISOString(),
    endsAt: new Date(draft.saleEndsAt).toISOString(),
  }
}
