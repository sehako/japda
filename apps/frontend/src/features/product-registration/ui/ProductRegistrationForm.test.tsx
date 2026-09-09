import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ProductRegistrationApiError,
  createProduct,
  createSale,
  uploadProductImages,
} from '../api/productRegistrationApi'
import { ProductRegistrationForm } from './ProductRegistrationForm'

vi.mock('../api/productRegistrationApi', async (importOriginal) => {
  const original =
    await importOriginal<typeof import('../api/productRegistrationApi')>()

  return {
    ...original,
    createProduct: vi.fn(),
    uploadProductImages: vi.fn(),
    createSale: vi.fn(),
  }
})

const createProductMock = vi.mocked(createProduct)
const uploadProductImagesMock = vi.mocked(uploadProductImages)
const createSaleMock = vi.mocked(createSale)

function productResponse() {
  return {
    id: 7,
    sellerId: 1,
    name: '한정판 상품',
    description: '상품 설명',
    status: 'DRAFT',
    createdAt: '2026-09-09T00:00:00Z',
  }
}

function imageResponse() {
  return { productId: 7, status: 'READY', images: [] }
}

function saleResponse() {
  return {
    id: 3,
    productId: 7,
    price: 120000,
    initialQuantity: 10,
    remainingQuantity: 10,
    startsAt: '2099-09-09T01:00:00.000Z',
    endsAt: '2099-09-10T01:00:00.000Z',
    status: 'UPCOMING',
    createdAt: '2026-09-09T00:00:00Z',
  }
}

async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('상품명'), '한정판 상품')
  await user.type(screen.getByLabelText('상품 설명'), '상품 설명')
  fireEvent.change(screen.getByLabelText('이미지 선택'), {
    target: {
      files: [new File(['image'], 'product.jpg', { type: 'image/jpeg' })],
    },
  })
  await user.type(screen.getByLabelText('판매 가격'), '120000')
  await user.type(screen.getByLabelText('판매 수량'), '10')
  fireEvent.change(screen.getByLabelText('판매 시작 시각'), {
    target: { value: '2099-09-09T10:00' },
  })
  fireEvent.change(screen.getByLabelText('판매 종료 시각'), {
    target: { value: '2099-09-10T10:00' },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('crypto', { randomUUID: vi.fn(() => 'image-id') })
  vi.stubGlobal('URL', {
    createObjectURL: vi.fn(() => 'blob:product'),
    revokeObjectURL: vi.fn(),
  })
  createProductMock.mockResolvedValue(productResponse())
  uploadProductImagesMock.mockResolvedValue(imageResponse())
  createSaleMock.mockResolvedValue(saleResponse())
})

describe('ProductRegistrationForm', () => {
  it('유효한 입력 전에는 등록할 수 없고 성공 시 세 요청을 순서대로 실행한다', async () => {
    const user = userEvent.setup()
    const order: string[] = []
    createProductMock.mockImplementation(async () => {
      order.push('product')
      return productResponse()
    })
    uploadProductImagesMock.mockImplementation(async () => {
      order.push('images')
      return imageResponse()
    })
    createSaleMock.mockImplementation(async () => {
      order.push('sale')
      return saleResponse()
    })
    render(<ProductRegistrationForm />)

    expect(screen.getByRole('button', { name: '상품 등록' })).toBeDisabled()
    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '상품 등록' }))

    expect(await screen.findByText('상품 등록이 완료되었습니다')).toBeVisible()
    expect(order).toEqual(['product', 'images', 'sale'])
  })

  it('pending 중 전체 form을 잠그고 중복 제출을 막는다', async () => {
    const user = userEvent.setup()
    let resolveProduct: ((value: ReturnType<typeof productResponse>) => void) | undefined
    createProductMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveProduct = resolve
        }),
    )
    render(<ProductRegistrationForm />)
    await fillValidForm(user)

    const submitButton = screen.getByRole('button', { name: '상품 등록' })
    await user.click(submitButton)

    expect(screen.getByRole('button', { name: '상품 등록 중…' })).toBeDisabled()
    expect(screen.getByLabelText('상품명')).toBeDisabled()
    fireEvent.submit(submitButton.closest('form')!)
    expect(createProductMock).toHaveBeenCalledTimes(1)

    resolveProduct?.(productResponse())
    expect(await screen.findByText('상품 등록이 완료되었습니다')).toBeVisible()
  })

  it('이미지 실패 뒤 상품을 다시 만들지 않고 이미지부터 재시도한다', async () => {
    const user = userEvent.setup()
    uploadProductImagesMock.mockRejectedValueOnce(new Error('upload failed'))
    render(<ProductRegistrationForm />)
    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '상품 등록' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('다시 시도')
    expect(screen.getByLabelText('상품명')).toBeDisabled()
    expect(screen.getByLabelText('이미지 선택')).not.toBeDisabled()

    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('상품 등록이 완료되었습니다')).toBeVisible()
    expect(createProductMock).toHaveBeenCalledTimes(1)
    expect(uploadProductImagesMock).toHaveBeenCalledTimes(2)
    expect(createSaleMock).toHaveBeenCalledTimes(1)
  })

  it('판매 실패 뒤 상품과 이미지를 반복하지 않고 판매만 재시도한다', async () => {
    const user = userEvent.setup()
    createSaleMock.mockRejectedValueOnce(new Error('sale failed'))
    render(<ProductRegistrationForm />)
    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '상품 등록' }))

    expect(await screen.findByRole('alert')).toBeVisible()
    expect(screen.getByLabelText('상품명')).toBeDisabled()
    expect(screen.getByLabelText('이미지 선택')).toBeDisabled()
    expect(screen.getByLabelText('판매 가격')).not.toBeDisabled()

    await user.clear(screen.getByLabelText('판매 가격'))
    await user.type(screen.getByLabelText('판매 가격'), '130000')
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('상품 등록이 완료되었습니다')).toBeVisible()
    expect(createProductMock).toHaveBeenCalledTimes(1)
    expect(uploadProductImagesMock).toHaveBeenCalledTimes(1)
    expect(createSaleMock).toHaveBeenCalledTimes(2)
  })

  it('새 상품 등록 시 입력과 object URL을 정리한다', async () => {
    const user = userEvent.setup()
    render(<ProductRegistrationForm />)
    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '상품 등록' }))
    await screen.findByText('상품 등록이 완료되었습니다')

    await user.click(screen.getByRole('button', { name: '새 상품 등록' }))

    await waitFor(() => expect(screen.getByLabelText('상품명')).toHaveValue(''))
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:product')
  })

  it('상품 생성의 서버 필드 오류를 입력에 연결하고 첫 오류로 이동한다', async () => {
    const user = userEvent.setup()
    createProductMock.mockRejectedValueOnce(
      new ProductRegistrationApiError('http', {
        status: 400,
        errors: { name: '이미 등록된 상품명입니다.' },
      }),
    )
    render(<ProductRegistrationForm />)
    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '상품 등록' }))

    expect(await screen.findByText('이미 등록된 상품명입니다.')).toBeVisible()
    expect(screen.getByLabelText('상품명')).toHaveFocus()
    expect(screen.getByLabelText('상품명')).not.toBeDisabled()
  })

  it('유효하지 않은 이미지 선택은 preview URL과 draft를 만들지 않는다', () => {
    render(<ProductRegistrationForm />)

    fireEvent.change(screen.getByLabelText('이미지 선택'), {
      target: {
        files: [new File(['image'], 'product.gif', { type: 'image/gif' })],
      },
    })

    expect(
      screen.getByText('JPEG, PNG 또는 WebP 이미지만 선택해 주세요.'),
    ).toBeVisible()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    expect(screen.getByText('선택한 이미지가 없습니다.')).toBeVisible()
  })
})
