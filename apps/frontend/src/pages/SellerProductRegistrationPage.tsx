import { ProductRegistrationForm } from '../features/product-registration/ui/ProductRegistrationForm'

export function SellerProductRegistrationPage() {
  return (
    <main className="mx-auto min-h-screen w-full max-w-[1440px] px-5 py-10 sm:px-8 lg:px-12 lg:py-16">
      <header className="max-w-3xl border-b border-concrete-gray pb-8 lg:pb-12">
        <h1 className="text-heading font-semibold tracking-[-0.03em] text-obsidian sm:text-[36px]">
          상품 등록
        </h1>
      </header>

      <div className="mt-10 lg:mt-12">
        <ProductRegistrationForm />
      </div>
    </main>
  )
}
