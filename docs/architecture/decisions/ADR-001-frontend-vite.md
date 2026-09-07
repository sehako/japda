# ADR-001: 프론트엔드 빌드 도구로 Vite 사용

- 상태: 승인
- 적용 영역: frontend
- 결정일: 2026-09-07

## 결정

`apps/frontend`는 Vite의 React + TypeScript 기본 템플릿으로 생성한다. 패키지는 npm으로 관리하고 TailwindCSS는 공식 Vite 플러그인으로 연동한다.

## 이유

기존 React + TypeScript + TailwindCSS 기술 스택에 맞춰 개발 서버와 빌드 환경을 최소 설정으로 구성한다. 초기 작업은 기본 스캐폴드 생성에 한정한다.

## 트레이드오프

Next.js 같은 프레임워크와 달리 라우팅과 서버 렌더링은 기본 제공하지 않는다. 해당 기능이 필요해지면 별도로 검토한다.
