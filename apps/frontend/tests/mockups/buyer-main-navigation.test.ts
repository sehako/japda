import { readFileSync } from 'node:fs'

import { JSDOM } from 'jsdom'
import { describe, expect, test } from 'vitest'

const mockupUrl = new URL('../../../../docs/mockups/buyer-main.html', import.meta.url)

describe('구매자 메인 목업 내비게이션', () => {
  test('로그인 사용자가 헤더에서 주문 내역으로 이동할 수 있다', () => {
    const html = readFileSync(mockupUrl, 'utf8')
    const { document } = new JSDOM(html, { url: 'https://japda.example/' }).window
    const navigation = document.querySelector('header nav[aria-label="주요 메뉴"]')

    expect(navigation?.querySelector('a[aria-current="page"]')?.textContent).toBe('SHOP')
    expect(navigation?.querySelector<HTMLAnchorElement>('a[href="buyer-order-history.html"]')?.textContent).toBe('ORDERS')
    expect(document.querySelector('[aria-label="로그인 사용자"]')?.textContent).toContain('hello@japda.kr')
  })
})
