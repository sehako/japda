import { useCurrentUser } from '../hook/useCurrentUser.ts'
import { startGoogleLogin } from '../util/loginFlow.ts'

const buttonClassName = 'min-h-10 min-w-20 rounded-full border border-[var(--color-obsidian)] bg-[var(--color-obsidian)] px-[18px] font-medium text-white hover:opacity-80 focus-visible:outline-2 focus-visible:outline-offset-3 md:min-h-[42px] md:min-w-[92px] md:px-[22px]'

export function AuthenticationStatus() {
  const { status, user, retry } = useCurrentUser()

  if (status === 'checking') {
    return <span aria-live="polite" className="text-sm text-[var(--color-steel)]">로그인 상태 확인 중</span>
  }

  if (status === 'authenticated') {
    return <span className="max-w-[50vw] truncate text-sm font-medium md:max-w-none">{user?.email}</span>
  }

  if (status === 'error') {
    return <div className="flex items-center gap-2 text-xs text-[var(--color-steel)] md:gap-3 md:text-sm">
      <span>로그인 상태를 확인하지 못했습니다</span>
      <button className="shrink-0 rounded-full border border-[var(--color-obsidian)] px-3 py-2 font-medium text-[var(--color-obsidian)] hover:bg-[var(--color-soft-mist)] focus-visible:outline-2 focus-visible:outline-offset-3" type="button" onClick={retry}>다시 시도</button>
    </div>
  }

  return <button className={buttonClassName} type="button" onClick={() => startGoogleLogin()}>로그인</button>
}
