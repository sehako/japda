import { afterEach, expect, test, vi } from 'vitest'

import { startGoogleLogin } from '../../../../src/features/authentication/util/loginFlow.ts'

vi.mock('../../../../src/shared/config/env.ts', () => ({ apiBaseUrl: 'https://api.japda.example/api' }))

afterEach(() => vi.unstubAllGlobals())

test('API 설정이 다른 origin이면 해당 origin의 OAuth2 시작 주소로 이동한다', () => {
  const assign = vi.fn()
  vi.stubGlobal('window', {
    location: { origin: 'https://shop.japda.example', pathname: '/', search: '', hash: '', assign },
  })

  startGoogleLogin(true)

  expect(assign).toHaveBeenCalledWith('https://api.japda.example/oauth2/authorization/google')
})
