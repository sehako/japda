import { useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { useCurrentUser } from '../../features/authentication/hook/useCurrentUser.ts'
import { consumeReturnPath, startGoogleLogin } from '../../features/authentication/util/loginFlow.ts'

export function AuthSuccessPage() {
  const { status, retry } = useCurrentUser()
  const navigate = useNavigate()

  useEffect(() => {
    if (status === 'authenticated') navigate(consumeReturnPath(), { replace: true })
  }, [status, navigate])

  const checking = status === 'checking' || status === 'authenticated'

  return <>
    <header className="h-[72px] border-b border-[var(--color-concrete-gray)]"><div className="mx-auto flex size-full max-w-[1440px] items-center px-8 max-[720px]:px-5"><Link className="text-xl font-semibold tracking-[-0.04em]" to="/" aria-label="JAPDA 홈">JAPDA</Link></div></header>
    <main className="mx-auto w-full max-w-[1200px] px-8 pt-20 pb-[120px] max-[720px]:px-5 max-[720px]:pt-12">
      <section className="flex min-h-[440px] items-center justify-center border-y border-[var(--color-concrete-gray)] text-center" role={checking ? 'status' : 'alert'} aria-live={checking ? 'polite' : undefined}>
        <div className="max-w-[480px] py-16">
          <p className="mb-[14px] text-[11px] font-semibold tracking-[0.16em] text-[var(--color-steel)]">로그인 결과</p>
          <h1 className="text-2xl font-semibold tracking-[-0.035em]">{checking ? '로그인 상태를 확인하고 있습니다.' : status === 'unauthenticated' ? '로그인이 필요합니다.' : '로그인 상태를 확인하지 못했습니다.'}</h1>
          {!checking ? <><p className="mt-4 text-base leading-[1.6]">{status === 'unauthenticated' ? '로그인 후 원래 화면으로 돌아갈 수 있습니다.' : '일시적인 오류가 발생했습니다. 다시 확인해 주세요.'}</p><button className="mt-8 min-h-[42px] rounded-full bg-[var(--color-obsidian)] px-6 font-medium text-white hover:opacity-80" type="button" onClick={status === 'unauthenticated' ? () => startGoogleLogin(true) : retry}>{status === 'unauthenticated' ? '로그인' : '다시 확인'}</button></> : null}
        </div>
      </section>
    </main>
  </>
}
