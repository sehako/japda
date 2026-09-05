# JAPDA — Style Reference

> product-first monochrome limited commerce — 한정판을 전시하고, 판매가 열리는 순간 잡는다.

**Theme:** mixed

JAPDA는 상품을 중심으로 한 monochrome commerce interface를 사용한다. UI는 최대한 뒤로 물러나고 상품 이미지, 판매 상태, 남은 시간과 수량이 화면의 중심이 된다. 기본 인터페이스는 black, white, gray로 구성하며 상품 사진이 대부분의 색감을 담당한다. 일반적인 장식용 accent color, gradient, shadow는 사용하지 않는다. 한정 판매의 긴장감은 `LIVE`, countdown, remaining quantity와 강한 black/white contrast로 표현한다. 상품과 콘텐츠 surface는 sharp edge를 사용하고, CTA와 status badge 같은 상호작용 요소에만 pill shape을 사용한다.

## Tokens — Colors

| Name          | Value     | Token                   | Role                                           |
| ------------- | --------- | ----------------------- | ---------------------------------------------- |
| Obsidian      | `#111111` | `--color-obsidian`      | 기본 텍스트, 아이콘, dark surface, primary CTA         |
| Paper White   | `#ffffff` | `--color-paper-white`   | 기본 페이지와 상품 카드 surface                          |
| Concrete Gray | `#e5e5e5` | `--color-concrete-gray` | Border, divider, table separator               |
| Soft Mist     | `#f5f5f5` | `--color-soft-mist`     | Search, secondary surface, disabled background |
| Steel         | `#707072` | `--color-steel`         | Secondary text, helper text, secondary icon    |
| Faint Gray    | `#9e9ea0` | `--color-faint-gray`    | Disabled, tertiary UI                          |
| Signal        | `#d92d20` | `--color-signal`        | LIVE, low stock, payment failure 등 긴급 상태       |

`Signal`은 일반 UI accent로 사용하지 않는다.

다음과 같이 즉각적인 상태 인지가 필요한 경우에만 사용한다.

* LIVE
* LOW STOCK
* 결제 실패
* 중요 경고

Primary action은 항상 Obsidian 또는 Paper White를 사용한다.

## Tokens — Typography

### Primary Sans — 기본 UI 및 본문 · `--font-primary`

* **Family:** Pretendard
* **Fallback:** Inter, system-ui, sans-serif
* **Weights:** 400, 500, 600
* **Sizes:** 12px, 14px, 16px, 20px
* **Role:** Navigation, body, button, product information, form, dashboard

### Display Sans — Hero 및 Editorial heading · `--font-display`

* **Family:** Pretendard
* **Fallback:** Inter, system-ui, sans-serif
* **Weights:** 500, 600
* **Sizes:** 24px, 48px, 76px
* **Role:** Drop hero, section title, countdown emphasis

일반 UI에서는 과도한 bold를 사용하지 않는다.

### Type Scale

| Role       | Size | Line Height | Token               |
| ---------- | ---- | ----------- | ------------------- |
| caption    | 12px | 1.5         | `--text-caption`    |
| body-sm    | 14px | 1.6         | `--text-body-sm`    |
| body       | 16px | 1.6         | `--text-body`       |
| subheading | 20px | 1.4         | `--text-subheading` |
| heading    | 24px | 1.25        | `--text-heading`    |
| section    | 48px | 1.1         | `--text-section`    |
| display    | 76px | 1.0         | `--text-display`    |

## Tokens — Spacing & Shapes

**Base unit:** 4px

**Density:** balanced

### Spacing Scale

| Name | Value | Token          |
| ---- | ----- | -------------- |
| 4    | 4px   | `--spacing-4`  |
| 8    | 8px   | `--spacing-8`  |
| 12   | 12px  | `--spacing-12` |
| 16   | 16px  | `--spacing-16` |
| 20   | 20px  | `--spacing-20` |
| 24   | 24px  | `--spacing-24` |
| 36   | 36px  | `--spacing-36` |
| 48   | 48px  | `--spacing-48` |
| 64   | 64px  | `--spacing-64` |

### Border Radius

| Element        | Value |
| -------------- | ----- |
| cards          | 0px   |
| product images | 0px   |
| inputs         | 0px   |
| panels         | 0px   |
| search         | 24px  |
| tags           | 30px  |
| buttons        | 30px  |

### Layout

* **Content max-width:** 1440px
* **Section gap:** 48–64px
* **Card padding:** 12–16px
* **Element gap:** 8–16px
* Hero 및 주요 editorial section은 full-bleed를 허용한다.

## Components

### Pill Button (Primary)

**Role:** 구매, 결제, 주요 행동을 위한 Primary CTA

White surface에서는 Obsidian fill과 white text를 사용한다.

Dark surface에서는 white fill과 Obsidian text를 사용한다.

* 30px radius
* 16px / weight 500
* Shadow 없음
* Gradient 없음
* 한 화면에서 경쟁하는 Primary CTA를 여러 개 배치하지 않는다.

예:

```text
SNAG NOW
BUY NOW
PAY ₩120,000
```

### Featured Drop Hero

**Role:** 현재 가장 중요한 한정 판매 상품을 강조하는 메인 영역

Full-bleed 상품 이미지를 중심으로 구성한다.

판매 전:

```text
LIMITED DROP

PRODUCT NAME

OPEN 18:00

02 : 14 : 32

[ VIEW DROP ]
```

판매 중:

```text
● LIVE
PRODUCT NAME

32 LEFT

[ SNAG NOW ]
```

품절:

```text
SOLD OUT
```

Dark 또는 high-contrast product photography를 사용할 수 있으며 CTA는 하나만 배치한다.

### Drop Card

**Role:** 오늘 또는 예정된 한정 판매 상품

* White surface
* Border 없음 또는 1px Concrete Gray
* Radius 없음
* Shadow 없음
* Product image가 가장 큰 요소
* 설명문은 최소화한다.
* 카드 전체에 여러 CTA를 넣지 않는다.

기본 정보:

```text
[ Product Image ]

LIVE / UPCOMING / SOLD OUT

Product Name
₩120,000

18:00 OPEN
```

Desktop은 3–4 columns, mobile은 1–2 columns를 기본으로 한다.

### Sale Status Badge

**Role:** 한정 판매의 현재 상태를 즉시 표시한다.

#### Upcoming

```text
UPCOMING
OPEN 18:00
```

Steel을 사용한다.

#### Live

```text
● LIVE
32 LEFT
```

Signal은 작은 dot, text 또는 핵심 수량에만 사용한다.

#### Sold Out

```text
SOLD OUT
```

Obsidian 또는 Steel을 사용한다.

상품 이미지는 필요하면 opacity 또는 saturation을 낮춘다.

#### Ended

```text
ENDED
```

가장 낮은 시각적 강조를 사용한다.

### Countdown

**Role:** 판매 시작 전 남은 시간을 강조한다.

```text
02 : 14 : 32
```

* 숫자를 가장 강하게 표현한다.
* 별도의 장식적인 timer UI를 만들지 않는다.
* 고정 폭 숫자를 사용해 layout shift를 최소화한다.
* Hero 또는 상품 상세에서만 강하게 강조한다.

### Product Detail

**Role:** 상품 정보와 구매 흐름을 제공한다.

Desktop:

```text
┌───────────────────┬────────────────┐
│                   │ LIMITED DROP   │
│                   │                │
│   Product Image   │ Product Name   │
│                   │ ₩120,000       │
│                   │                │
│                   │ ● LIVE         │
│                   │ 32 LEFT        │
│                   │                │
│                   │ [ SNAG NOW ]   │
└───────────────────┴────────────────┘
```

정보 우선순위:

1. 판매 상태
2. 상품명
3. 가격
4. 판매 시작 시간 또는 countdown
5. 남은 수량
6. 구매 CTA
7. 상품 설명

### Checkout / Payment Form

**Role:** 주문 및 실제 결제를 명확하게 처리한다.

* White background
* Square input
* Concrete Gray border
* Shadow 없음
* 충분한 field spacing
* 금액을 명확하게 구분한다.

Primary CTA:

```text
PAY ₩120,000
```

상태는 명확한 텍스트를 사용한다.

```text
결제 진행 중
결제 완료
결제 실패
```

### Top Navigation Bar

**Role:** 서비스의 주요 화면과 사용자 기능으로 이동한다.

예:

```text
JAPDA

TODAY
UPCOMING

Search
Account
Orders
```

* White background
* Shadow 없음
* 메뉴 수 최소화
* Search는 Soft Mist + 24px radius
* Icon은 outline 형태를 사용한다.

### Seller / Admin Table

**Role:** 판매, 주문, 결제, 정산 데이터를 관리한다.

* White background
* 0px radius
* Shadow 없음
* Concrete Gray row divider
* 상태 표시에만 pill badge 사용
* 불필요한 dashboard card나 decorative chart를 추가하지 않는다.

### Icon System

**Role:** Search, Account, Order 등 utility action

* Outline icon
* 1.5–2px stroke
* 16–20px
* Obsidian 기본
* Steel secondary
* Signal icon은 오류 또는 긴급 상태에만 허용한다.

## Do's and Don'ts

### Do

* 상품 이미지를 화면에서 가장 강한 시각 요소로 사용한다.
* Black, White, Gray를 기본 palette로 사용한다.
* CTA는 pill 형태로 일관되게 사용한다.
* 상품 카드와 이미지에는 0px radius를 사용한다.
* Shadow보다 whitespace, border, surface contrast를 사용한다.
* LIVE, UPCOMING, SOLD OUT 상태를 텍스트로 명확하게 표시한다.
* Countdown과 남은 수량을 한정 판매의 핵심 정보로 다룬다.
* Signal color는 긴급 상태에만 제한적으로 사용한다.
* 사용자 화면은 editorial하게, seller/admin 화면은 기능적으로 구성한다.

### Don't

* 장식용 gradient를 사용하지 않는다.
* 일반 UI에 accent color를 남용하지 않는다.
* Product Card에 shadow를 사용하지 않는다.
* Product Card를 둥근 카드 형태로 만들지 않는다.
* 한 화면에 여러 Primary CTA를 경쟁시키지 않는다.
* 상태를 색상만으로 표현하지 않는다.
* Dashboard를 의미 없는 card grid로 구성하지 않는다.
* 상품보다 UI decoration을 강조하지 않는다.
* Glassmorphism을 사용하지 않는다.
* 과도한 animation을 사용하지 않는다.

## Surfaces

| Level | Name          | Value     | Purpose                               |
| ----- | ------------- | --------- | ------------------------------------- |
| 1     | Paper White   | `#ffffff` | 기본 화면, product grid, form             |
| 2     | Soft Mist     | `#f5f5f5` | Search, secondary area                |
| 3     | Concrete Gray | `#e5e5e5` | Border, divider                       |
| 4     | Obsidian      | `#111111` | Editorial hero, high contrast surface |

## Elevation

JAPDA는 기본적으로 box-shadow를 사용하지 않는다.

Depth는 다음 요소로 표현한다.

1. White와 Obsidian surface의 강한 대비
2. Concrete Gray hairline border
3. 충분한 whitespace
4. 상품 이미지와 배경의 contrast

Modal, Popover 등 실제 overlay 관계가 있는 경우에만 제한적인 shadow를 허용한다.

## Imagery

상품 사진은 JAPDA의 가장 중요한 시각 요소다.

* 고해상도 상품 사진
* 제품 형태가 명확한 crop
* 상품 단독 이미지 우선
* Grid에서는 일관된 aspect ratio
* Hero에서는 크고 과감한 crop 허용
* Product image container는 sharp edge 유지
* 이미지 위 텍스트는 최소화한다.

상품 사진 자체의 색상은 제한하지 않는다.

UI가 monochrome이므로 상품의 색과 소재가 자연스럽게 화면의 chromatic focus가 된다.

다음 이미지는 지양한다.

* UI 장식을 위한 stock photo
* 장식용 illustration
* 추상 graphic
* 카드마다 다른 image geometry
* 필요 이상의 gradient overlay

## Layout

페이지는 다음 editorial rhythm을 기본으로 한다.

```text
Navigation
↓
Featured Drop Hero
↓
Today's Drops
↓
Upcoming Drops
↓
Footer
```

Hero는 넓고 여유롭게 구성한다.

상품 Grid는 Hero보다 compact하게 구성한다.

상품 상세는 Desktop에서 Product Image와 Information의 2-column layout을 기본으로 한다.

### Responsive

Desktop:

* Hero large typography
* 3–4 column drop grid
* Product detail 2-column
* 넓은 product photography

Mobile:

* 1–2 column grid
* Hero typography 축소
* Primary CTA touch area 확보
* 구매 화면에서 필요하면 CTA를 bottom fixed로 제공
* 가격과 판매 상태를 scroll 이전에 노출한다.

## Agent Prompt Guide

**Quick Color Reference**

* text: `#111111`
* background: `#ffffff`
* border: `#e5e5e5`
* secondary surface: `#f5f5f5`
* secondary text: `#707072`
* urgent state: `#d92d20`
* primary action: Obsidian on white / white on Obsidian

**Design Priority**

```text
Product
→ Sale Status
→ Time / Quantity
→ CTA
→ Supporting UI
```

디자인 판단이 모호하면 더 단순한 방향을 선택한다.

```text
Less color
Less decoration
Less shadow
Less radius

More product
More whitespace
Clearer status
```

### Example Component Prompts

1. Create a full-bleed Featured Drop Hero with an Obsidian or high-contrast product background. Display one large product image as the dominant element. Show `LIMITED DROP`, product name, opening time or LIVE state, countdown or remaining quantity, and one pill CTA. Do not add decorative graphics, cards, gradients, or multiple CTAs.

2. Create a Today's Drops grid with 3–4 sharp-edged product cards on desktop. Each card has a large product image, sale status, product name, price and opening time. Cards have no radius and no shadow.

3. Create a live Drop Card with `● LIVE` and remaining quantity clearly visible. Use Signal only for the LIVE indicator or urgent quantity state. Keep the product image dominant.

4. Create a Product Detail screen with a large product image on the left and sale information on the right. Show sale status, product name, price, countdown or remaining quantity and one `SNAG NOW` CTA.

5. Create a seller order table on a white surface with Concrete Gray row dividers, no card shadow and no rounded container. Use compact pill badges only for status.

## Similar Brands

* **Nike** — Product-first monochrome commerce, sharp product surfaces and minimal UI chrome
* **SSENSE** — High-contrast editorial e-commerce with restrained typography and whitespace
* **Apple** — Neutral UI that allows product imagery to carry most visual emphasis
* **Adidas** — Large campaign imagery combined with practical commerce components

## Quick Start

### CSS Custom Properties

```css
:root {
  /* Colors */
  --color-obsidian: #111111;
  --color-paper-white: #ffffff;
  --color-concrete-gray: #e5e5e5;
  --color-soft-mist: #f5f5f5;
  --color-steel: #707072;
  --color-faint-gray: #9e9ea0;
  --color-signal: #d92d20;

  /* Typography */
  --font-primary:
    "Pretendard",
    "Inter",
    ui-sans-serif,
    system-ui,
    sans-serif;

  --font-display:
    "Pretendard",
    "Inter",
    ui-sans-serif,
    system-ui,
    sans-serif;

  --text-caption: 12px;
  --text-body-sm: 14px;
  --text-body: 16px;
  --text-subheading: 20px;
  --text-heading: 24px;
  --text-section: 48px;
  --text-display: 76px;

  /* Spacing */
  --spacing-unit: 4px;
  --spacing-4: 4px;
  --spacing-8: 8px;
  --spacing-12: 12px;
  --spacing-16: 16px;
  --spacing-20: 20px;
  --spacing-24: 24px;
  --spacing-36: 36px;
  --spacing-48: 48px;
  --spacing-64: 64px;

  /* Layout */
  --page-max-width: 1440px;
  --section-gap: 64px;
  --card-padding: 16px;
  --element-gap: 8px;

  /* Border Radius */
  --radius-cards: 0px;
  --radius-inputs: 0px;
  --radius-search: 24px;
  --radius-tags: 30px;
  --radius-buttons: 30px;

  /* Surfaces */
  --surface-paper-white: #ffffff;
  --surface-soft-mist: #f5f5f5;
  --surface-concrete-gray: #e5e5e5;
  --surface-obsidian: #111111;
}
```

### Tailwind v4

```css
@theme {
  /* Colors */
  --color-obsidian: #111111;
  --color-paper-white: #ffffff;
  --color-concrete-gray: #e5e5e5;
  --color-soft-mist: #f5f5f5;
  --color-steel: #707072;
  --color-faint-gray: #9e9ea0;
  --color-signal: #d92d20;

  /* Typography */
  --font-sans:
    "Pretendard",
    "Inter",
    ui-sans-serif,
    system-ui,
    sans-serif;

  --text-caption: 12px;
  --text-body-sm: 14px;
  --text-body: 16px;
  --text-subheading: 20px;
  --text-heading: 24px;
  --text-section: 48px;
  --text-display: 76px;

  /* Spacing */
  --spacing-4: 4px;
  --spacing-8: 8px;
  --spacing-12: 12px;
  --spacing-16: 16px;
  --spacing-20: 20px;
  --spacing-24: 24px;
  --spacing-36: 36px;
  --spacing-48: 48px;
  --spacing-64: 64px;

  /* Border Radius */
  --radius-card: 0px;
  --radius-input: 0px;
  --radius-search: 24px;
  --radius-tag: 30px;
  --radius-button: 30px;
}
```
