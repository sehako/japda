import { BrowserRouter, Route, Routes } from 'react-router-dom'

import { BuyerMainPage } from '../pages/buyer-main/BuyerMainPage.tsx'
import { NotFoundPage } from '../pages/not-found/NotFoundPage.tsx'
import { SellerProductRegistrationPage } from '../pages/seller-product-registration/SellerProductRegistrationPage.tsx'
import { SellerSaleSchedulingPage } from '../pages/seller-sale-scheduling/SellerSaleSchedulingPage.tsx'

export function AppRouter() {
  return <BrowserRouter><Routes><Route path="/" element={<BuyerMainPage />} /><Route path="/seller/products/new" element={<SellerProductRegistrationPage />} /><Route path="/seller/sales/new" element={<SellerSaleSchedulingPage />} /><Route path="*" element={<NotFoundPage />} /></Routes></BrowserRouter>
}
