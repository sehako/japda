import { useProductRegistration } from '../../features/product-registration/hook/useProductRegistration'
import { ProductRegistrationForm } from '../../features/product-registration/ui/ProductRegistrationForm'

export function SellerProductRegistrationPage() {
  const registration = useProductRegistration()

  return <><header className="h-16 border-b border-[var(--color-concrete-gray)] md:h-[72px]"><div className="mx-auto flex size-full max-w-[1440px] items-center justify-between px-5 md:px-8"><div className="flex items-baseline gap-3"><span className="text-xl font-semibold tracking-[-0.04em]">JAPDA</span><span className="text-xs font-semibold tracking-[0.12em] text-[var(--color-steel)] max-md:hidden">SELLER</span></div><span className="text-[13px] max-md:hidden">상품 등록</span></div></header>
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
      onStartNew={registration.startNew}
    /></main></>
}
