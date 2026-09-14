import { Link, useSearchParams } from 'react-router-dom'

import { startGoogleLogin } from '../../features/authentication/util/loginFlow.ts'

export function AuthFailurePage() {
  const [searchParams] = useSearchParams()
  const emailUnverified = searchParams.get('error') === 'EMAIL_UNVERIFIED'

  return <>
    <header className="h-[72px] border-b border-[var(--color-concrete-gray)]"><div className="mx-auto flex size-full max-w-[1440px] items-center px-8 max-[720px]:px-5"><Link className="text-xl font-semibold tracking-[-0.04em]" to="/" aria-label="JAPDA 홈">JAPDA</Link></div></header>
    <main className="mx-auto w-full max-w-[1200px] px-8 pt-20 pb-[120px] max-[720px]:px-5 max-[720px]:pt-12">
      <section className="flex min-h-[440px] items-center justify-center border-y border-[var(--color-concrete-gray)] text-center" role="alert">
        <div className="max-w-[480px] py-16">
          <p className="mb-[14px] text-[11px] font-semibold tracking-[0.16em] text-[var(--color-steel)]">로그인 결과</p>
          <h1 className="text-2xl font-semibold tracking-[-0.035em]">{emailUnverified ? '검증된 이메일이 필요합니다.' : 'Google 로그인에 실패했습니다.'}</h1>
          <p className="mt-4 text-base leading-[1.6]">{emailUnverified ? 'Google 계정의 이메일 인증을 완료한 뒤 다시 시도해 주세요.' : '잠시 후 다시 시도해 주세요.'}</p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-4"><button className="min-h-[42px] rounded-full bg-[var(--color-obsidian)] px-6 font-medium text-white hover:opacity-80" type="button" onClick={() => startGoogleLogin(true)}>Google 로그인 다시 시도</button><Link className="inline-flex min-h-[42px] items-center text-[var(--color-steel)] underline underline-offset-[3px] hover:text-[var(--color-obsidian)]" to="/">메인으로 이동</Link></div>
        </div>
      </section>
    </main>
  </>
}
