import { ProductRegistrationForm } from '../features/product-registration/ui/ProductRegistrationForm'

export function SellerProductRegistrationPage() {
  return (
    <main className="mx-auto min-h-screen w-full max-w-[1440px] px-5 py-10 sm:px-8 lg:px-12 lg:py-16">
      <header className="max-w-3xl border-b border-concrete-gray pb-8 lg:pb-12">
        <p className="mb-3 text-caption font-medium tracking-[0.16em] text-steel uppercase">
          판매자 작업 공간
        </p>
        <h1 className="text-heading font-semibold tracking-[-0.03em] text-obsidian sm:text-[36px]">
          상품 등록
        </h1>
        <p className="mt-4 max-w-2xl text-body-sm text-steel sm:text-body">
          상품 정보와 이미지, 판매 조건을 한 번에 입력해 등록합니다. 일부
          단계에서 실패해도 완료된 작업은 유지한 채 다시 시도할 수 있습니다.
        </p>
      </header>

      <div className="mt-10 lg:mt-12">
        <ProductRegistrationForm />
      </div>
    </main>
  )
}
