import assert from 'node:assert/strict'
import { test } from 'vitest'

import {
  adjustRepresentativeIndexAfterRemoval,
  mapApiErrorToRegistrationErrors,
  validateProductRegistration,
} from '../../../../src/features/product-registration/model/productRegistration.ts'
import { ApiError } from '../../../../src/shared/api/apiClient.ts'

const MiB = 1024 * 1024

function image(name: string, size: number, type = 'image/jpeg'): File {
  return new File([new Uint8Array(size)], name, { type })
}

test('상품명과 설명은 앞뒤 공백을 제거하고 공백 설명은 null로 정규화한다', () => {
  const result = validateProductRegistration({
    name: '  한정판 상품  ',
    description: '   ',
    files: [image('product.jpg', 1)],
    representativeIndex: 0,
  })

  assert.deepEqual(result, {
    valid: true,
    value: {
      name: '한정판 상품',
      description: null,
      files: result.valid ? result.value.files : [],
      representativeIndex: 0,
    },
  })
})

test('상품명, 이미지 형식과 용량, 대표 이미지 오류를 필드별로 반환한다', () => {
  const result = validateProductRegistration({
    name: ' '.repeat(2),
    description: '설명',
    files: [image('huge.gif', 10 * MiB + 1, 'image/gif')],
    representativeIndex: 2,
  })

  assert.equal(result.valid, false)
  if (result.valid) return
  assert.equal(result.errors.name, '상품명을 입력해 주세요.')
  assert.match(result.errors.files ?? '', /JPEG, PNG, WebP/)
  assert.match(result.errors.files ?? '', /10MiB/)
  assert.equal(result.errors.representativeIndex, '대표 이미지를 지정해 주세요.')
})

test('10장과 전체 50MiB는 허용하고 11장과 50MiB 초과는 거부한다', () => {
  const tenFiles = Array.from({ length: 10 }, (_, index) => image(`${index}.jpg`, 5 * MiB))
  assert.equal(validateProductRegistration({ name: '상품', description: '', files: tenFiles, representativeIndex: 9 }).valid, true)

  const tooMany = [...tenFiles, image('extra.jpg', 1)]
  const countResult = validateProductRegistration({ name: '상품', description: '', files: tooMany, representativeIndex: 0 })
  assert.equal(countResult.valid, false)

  const tooLarge = [...tenFiles.slice(0, 9), image('last.jpg', 5 * MiB + 1)]
  const sizeResult = validateProductRegistration({ name: '상품', description: '', files: tooLarge, representativeIndex: 0 })
  assert.equal(sizeResult.valid, false)
  if (!sizeResult.valid) assert.match(sizeResult.errors.files ?? '', /50MiB/)
})

test('대표 이미지 앞의 파일을 삭제하면 동일한 대표 이미지를 가리키도록 인덱스를 줄인다', () => {
  assert.equal(adjustRepresentativeIndexAfterRemoval(3, 1, 4), 2)
})

test('대표 이미지를 삭제하면 남은 첫 이미지를 대표로 지정하고 목록이 비면 해제한다', () => {
  assert.equal(adjustRepresentativeIndexAfterRemoval(1, 1, 3), 0)
  assert.equal(adjustRepresentativeIndexAfterRemoval(0, 0, 1), null)
})

test('ProblemDetail 필드 오류는 폼 필드와 화면 오류로 구분한다', () => {
  const apiError = new ApiError('요청에 실패했습니다.', {
    status: 400,
    code: 'PRODUCT_NAME_REQUIRED',
    detail: '요청 값이 올바르지 않습니다.',
    errors: { name: '상품명은 필수입니다.', productId: '상품을 찾을 수 없습니다.' },
  })

  assert.deepEqual(mapApiErrorToRegistrationErrors(apiError), {
    fieldErrors: { name: '상품명은 필수입니다.' },
    formError: '상품을 찾을 수 없습니다.',
    blocksSubmission: false,
  })
})

test('이미지 중복 등록 충돌은 안내 문구를 표시하고 추가 제출을 막는다', () => {
  const apiError = new ApiError('요청에 실패했습니다.', {
    status: 409,
    code: 'PRODUCT_IMAGES_ALREADY_REGISTERED',
    detail: '현재 상태와 요청이 충돌합니다.',
    errors: {},
  })

  assert.deepEqual(mapApiErrorToRegistrationErrors(apiError), {
    fieldErrors: {},
    formError: '이미지가 이미 등록되었거나 이전 요청이 완료되었습니다. 상품 상태를 확인해 주세요.',
    blocksSubmission: true,
  })
})

test.each([
  ['AUTH_UNAUTHENTICATED', '로그인이 필요합니다. 다시 로그인해 주세요.'],
  ['AUTH_SELLER_LINK_REQUIRED', '판매자 계정 연결이 필요합니다.'],
  ['AUTH_CSRF_INVALID', '보안 확인에 실패했습니다. 다시 시도해 주세요.'],
])('상품 등록 인증 오류 %s는 일반 오류 대신 전용 안내를 사용한다', (code, formError) => {
  const result = mapApiErrorToRegistrationErrors(new ApiError('실패', { status: code === 'AUTH_UNAUTHENTICATED' ? 401 : 403, code }))
  assert.equal(result.formError, formError)
})
