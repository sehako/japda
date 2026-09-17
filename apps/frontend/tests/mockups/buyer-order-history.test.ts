import { readFileSync } from 'node:fs'

import { JSDOM } from 'jsdom'
import { afterEach, describe, expect, test } from 'vitest'

const mockupUrl = new URL('../../../../docs/mockups/buyer-order-history.html', import.meta.url)
const openDoms: JSDOM[] = []

function openMockup(search = ''): JSDOM {
  const html = readFileSync(mockupUrl, 'utf8')
  const dom = new JSDOM(html, {
    pretendToBeVisual: true,
    runScripts: 'dangerously',
    url: `https://japda.example/orders${search}`,
  })
  openDoms.push(dom)
  return dom
}

afterEach(() => {
  openDoms.splice(0).forEach((dom) => dom.window.close())
})

describe('구매자 주문 내역 목업', () => {
  test('기본 화면에서 최신 주문과 결제 상태를 표시한다', () => {
    const { document } = openMockup().window

    expect(document.querySelector('h1')?.textContent).toBe('Orders')
    expect(document.querySelectorAll('[data-order-row]')).toHaveLength(3)
    expect(document.querySelector('[data-order-row]')?.textContent).toContain('JAPDA × ORBIT 레더 스니커즈')
    expect(document.querySelector('[data-order-row]')?.textContent).toContain('결제 완료')
    expect(document.querySelector('#load-more')?.hasAttribute('hidden')).toBe(false)
  })

  test('주문 더 불러오기를 누르면 다음 주문을 이어 붙이고 버튼을 숨긴다', async () => {
    const { window } = openMockup()
    const button = window.document.querySelector<HTMLButtonElement>('#load-more')

    button?.click()
    expect(button?.textContent).toBe('불러오는 중')
    await new Promise((resolve) => window.setTimeout(resolve, 500))

    expect(window.document.querySelectorAll('[data-order-row]')).toHaveLength(5)
    expect(button?.hasAttribute('hidden')).toBe(true)
    expect(window.document.querySelector('#load-feedback')?.textContent).toBe('주문 2건을 더 불러왔습니다.')
  })

  test.each([
    ['?state=empty', '아직 구매 내역이 없습니다.'],
    ['?state=error', '주문 내역을 불러오지 못했습니다.'],
    ['?state=unauthenticated', '로그인이 필요합니다.'],
    ['?state=buyer-link', '구매자 정보 연결이 필요합니다.'],
  ])('%s 상태를 독립적인 안내 화면으로 표시한다', (search, message) => {
    const { document } = openMockup(search).window

    expect(document.querySelector('[data-state-view]:not([hidden]) h2')?.textContent).toBe(message)
    expect(document.querySelectorAll('[data-order-row]')).toHaveLength(0)
  })
})
