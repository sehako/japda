import type {
  CreateProductRequest,
  CreateSaleRequest,
  ProblemDetail,
  ProductImageUploadResponse,
  ProductResponse,
  SaleResponse,
  UploadProductImagesRequest,
} from '../model/productRegistration'

const SELLER_HEADERS = { 'X-Seller-Id': '1' }

export class ProductRegistrationApiError extends Error {
  readonly kind: 'http' | 'network'
  readonly problem?: ProblemDetail

  constructor(
    kind: 'http' | 'network',
    problem?: ProblemDetail,
  ) {
    super(
      kind === 'network'
        ? '네트워크 연결을 확인한 뒤 다시 시도해 주세요.'
        : (problem?.detail ?? '상품 등록 요청을 처리하지 못했습니다.'),
    )
    this.name = 'ProductRegistrationApiError'
    this.kind = kind
    this.problem = problem
  }
}

async function parseBody(response: Response): Promise<unknown> {
  const text = await response.text()

  if (text === '') {
    return undefined
  }

  try {
    return JSON.parse(text) as unknown
  } catch {
    return undefined
  }
}

async function request<T>(url: string, options: RequestInit): Promise<T> {
  let response: Response

  try {
    response = await fetch(url, options)
  } catch {
    throw new ProductRegistrationApiError('network')
  }

  const body = await parseBody(response)

  if (!response.ok) {
    const problem =
      typeof body === 'object' && body !== null
        ? (body as ProblemDetail)
        : { status: response.status }

    throw new ProductRegistrationApiError('http', {
      ...problem,
      status: problem.status ?? response.status,
    })
  }

  return body as T
}

function jsonRequest(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...SELLER_HEADERS,
    },
    body: JSON.stringify(body),
  }
}

export function createProduct(
  requestBody: CreateProductRequest,
): Promise<ProductResponse> {
  return request<ProductResponse>('/api/products', jsonRequest(requestBody))
}

export function uploadProductImages(
  productId: number,
  requestBody: UploadProductImagesRequest,
): Promise<ProductImageUploadResponse> {
  const body = new FormData()

  for (const file of requestBody.files) {
    body.append('files', file)
  }
  body.append('representativeIndex', String(requestBody.representativeIndex))

  return request<ProductImageUploadResponse>(
    `/api/products/${productId}/images`,
    {
      method: 'POST',
      headers: SELLER_HEADERS,
      body,
    },
  )
}

export function createSale(
  productId: number,
  requestBody: CreateSaleRequest,
): Promise<SaleResponse> {
  return request<SaleResponse>(
    `/api/products/${productId}/sales`,
    jsonRequest(requestBody),
  )
}
