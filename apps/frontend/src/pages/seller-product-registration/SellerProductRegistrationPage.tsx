import { Link, useNavigate } from 'react-router-dom'

import { useProductRegistration } from '../../features/product-registration/hook/useProductRegistration'
import { ProductRegistrationForm } from '../../features/product-registration/ui/ProductRegistrationForm'
import { useCurrentUser } from '../../features/authentication/hook/useCurrentUser.ts'
import { startGoogleLogin } from '../../features/authentication/util/loginFlow.ts'

function SellerProductRegistrationContent() {
  const registration = useProductRegistration()
  const navigate = useNavigate()

  if (registration.authError) return <SellerAccessState status={registration.authError} />

  return <><header className="h-16 border-b border-[var(--color-concrete-gray)] md:h-[72px]"><div className="mx-auto flex size-full max-w-[1440px] items-center justify-between px-5 md:px-8"><Link className="flex items-baseline gap-3" to="/seller/products/new"><span className="text-xl font-semibold tracking-[-0.04em]">JAPDA</span><span className="text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)] max-md:hidden">SELLER</span></Link><nav className="flex gap-6 text-[13px]" aria-label="판매자 메뉴"><Link className="font-semibold" to="/seller/products/new" aria-current="page">상품 등록</Link><Link className="text-[var(--color-steel)]" to="/seller/sales/new">판매 일정 등록</Link></nav></div></header>
    <main className="mx-auto w-full max-w-[1280px] px-5 pt-10 pb-28 md:px-8 md:pt-16 md:pb-20"><header className="mb-7 md:mb-9"><p className="mb-2 text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)]">PRODUCT REGISTRATION</p><h1 className="text-[clamp(32px,4vw,48px)] leading-[1.1] font-semibold tracking-[-0.04em]">상품 등록</h1><p className="mt-3 text-sm text-[var(--color-steel)]">상품 기본 정보와 대표 이미지를 등록해 주세요.</p></header><ProductRegistrationForm
      name={registration.name}
      description={registration.description}
      images={registration.images}
      representativeIndex={registration.representativeIndex}
      submissionStage={registration.submissionStage}
      fieldErrors={registration.fieldErrors}
      formError={registration.formError ?? undefined}
      createdProductId={registration.createdProductId}
      onNameChange={registration.changeName}
      onDescriptionChange={registration.changeDescription}
      onImagesAdd={registration.addImages}
      onImageRemove={registration.removeImage}
      onRepresentativeSelect={registration.selectRepresentative}
      onSubmit={registration.submit}
      onCancel={registration.cancel}
      onScheduleSale={() => {
        if (!registration.registeredProduct) return
        navigate('/seller/sales/new', { state: { preselectedProduct: registration.registeredProduct } })
      }}
      onStartNew={registration.startNew}
    /></main></>
}

function SellerAccessState({ status, retry }: { status: 'checking' | 'unauthenticated' | 'seller-link-required' | 'error'; retry?: () => void }) {
  const message = status === 'checking' ? '로그인 상태를 확인하는 중입니다.'
    : status === 'unauthenticated' ? '로그인이 필요합니다. 다시 로그인해 주세요.'
      : status === 'seller-link-required' ? '판매자 계정 연결이 필요합니다.' : '로그인 상태를 확인하지 못했습니다.'
  return <main className="mx-auto max-w-[1280px] px-5 py-20 text-center" role="alert">
    <p>{message}</p>
    {status === 'unauthenticated' ? <button className="mt-6 rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" type="button" onClick={() => startGoogleLogin()}>Google 로그인</button> : null}
    {status === 'error' ? <button className="mt-6 rounded-full bg-[var(--color-obsidian)] px-6 py-3 text-white" type="button" onClick={retry}>다시 확인</button> : null}
  </main>
}

export function SellerProductRegistrationPage() {
  const { status, retry } = useCurrentUser()
  return status === 'authenticated' ? <SellerProductRegistrationContent /> : <SellerAccessState status={status} retry={retry} />
}
