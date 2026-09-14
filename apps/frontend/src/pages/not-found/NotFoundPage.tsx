import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return <main className="mx-auto max-w-[1120px] px-5 py-20"><p className="text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)]">404 NOT FOUND</p><h1 className="mt-2 text-4xl font-semibold">페이지를 찾을 수 없습니다.</h1><Link className="mt-8 inline-block rounded-full bg-[var(--color-obsidian)] px-7 py-3 text-white" to="/">메인 페이지로 이동</Link></main>
}
