# ADR-030: 판매자 일일 정산에 로컬 파티셔닝 적용

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-09-18

## 결정

판매자 일일 정산 Job의 상세 수집과 지갑 입금 Step에 Spring Batch local partitioning을 적용한다. 단일 배치 JVM과 단일 PostgreSQL은 유지하며 collection과 credit worker는 동시에 실행하지 않고 하나의 전용 bounded worker pool을 공유한다. 논리 파티션 수는 worker 수 이상으로 구성하고 manager Step이 각 worker의 완료 여부를 조율한다. 판매자별 집계·확정 Step은 PostgreSQL 집합 연산을 사용하는 단일 실행으로 유지한다.

파티션은 결제 ID와 판매자별 정산 ID의 결정론적인 상호 배타 범위로 나눈다. 준비 Step이 최소·최대 ID와 설정된 파티션 수로 `[startInclusive, endExclusive)` 범위를 한 번 계산하고, `Long.MAX_VALUE` 상한은 마지막 파티션의 포함 조건으로 표현한다. 파티션 이름, 실제 파티션 수와 각 경계를 고정 key의 기본값으로 Job `ExecutionContext`에 저장한다. 동일 JobInstance 재시작에서는 저장된 계획만 복원하고 현재 데이터나 변경된 설정으로 다시 계산하지 않는다. 전체 대상 또는 처리 완료 ID 목록과 직렬화한 임의 객체는 저장하지 않는다.

각 worker는 자기 범위 안에서 ID 오름차순 keyset paging과 단일 thread chunk 처리를 수행하고 마지막 commit cursor를 자신의 Step `ExecutionContext`에 저장한다. chunk의 업무 데이터 변경, cursor와 Spring Batch 메타데이터는 같은 PostgreSQL transaction manager로 함께 commit한다. 재시작은 완료된 worker를 건너뛰고 실패하거나 미완료된 worker만 마지막 commit 다음부터 처리한다. 저장된 계획이나 cursor가 누락·중첩·역전 또는 범위 밖이면 자동 보정하지 않고 Job을 실패시킨다.

credit worker는 시작 시 `settlement_runs` 상태를 잠금 없이 확인하고, 각 chunk에서 공통으로 수행하던 `settlement_runs FOR UPDATE` 조회를 제거한다. 대신 대상 `seller_settlements`와 사용자 지갑 행의 잠금, 원장 source 유일 제약, 동일 transaction의 잔액 증가·원장 생성·`CREDITED` 전이를 유지한다. 모든 credit worker가 완료된 뒤 단일 완료 Step이 전체 판매자별 정산 상태, 양수 순액별 원장 존재, 원장 건수와 금액을 검산하고 일치할 때만 실행을 `COMPLETED`로 전이한다. Spring Batch JobRepository와 `settlement_runs.settlement_date` 유일 제약으로 같은 정산일의 동시 실행을 차단한다.

이 결정은 [ADR-025](ADR-025-daily-seller-settlement-and-user-wallet-ledger.md)의 정산 근거 보존, 사용자 귀속 지갑, 멱등성과 금액 불변식은 유지하고 단일 파티션·단일 thread 실행 모델만 대체한다.

## 이유

공유 reader를 여러 thread가 사용하는 방식보다 각 worker에 고정 ID 범위와 독립 checkpoint를 부여하면 상태 경쟁 없이 병렬 처리와 실패 복구를 함께 제공할 수 있다. Job `ExecutionContext`의 결정론적 계획은 재시작 시 데이터 증가나 설정 변경 때문에 파티션 경계가 달라지는 것을 막고, Spring Batch 메타데이터를 진행 상태의 단일 기준으로 유지한다.

collection과 credit은 Job 흐름상 겹치지 않으므로 bounded worker pool을 공유하면 불필요한 thread pool을 늘리지 않으면서 동시성과 queue 크기를 제한할 수 있다. 지갑 입금에서 모든 worker가 같은 실행 행을 잠그지 않게 하면 공통 lock 직렬화를 제거할 수 있다. 개별 판매자 정산과 지갑의 잠금, 원장의 멱등 제약, worker 완료 뒤 전체 검산을 결합하면 실행 행 잠금을 제거해도 중복 입금과 부분 완료를 탐지할 수 있다.

## 트레이드오프

ID 공간을 같은 폭으로 나누므로 삭제나 생성 패턴에 따라 파티션별 행 수가 달라질 수 있다. worker 수보다 많은 논리 파티션과 파티션별 처리 시간 관측으로 편향을 완화하지만, 심한 편향은 실제 분포와 실행계획을 근거로 별도 경계 전략을 설계해야 한다.

파티션 계획과 worker checkpoint를 검증·복원하는 코드, manager와 worker Step, 전용 executor 설정이 추가되어 단일 thread 구성보다 복잡하다. 이미 commit된 다른 worker 결과를 실패 시 되돌리지 않으므로 재시작 계약과 데이터베이스 유일 제약을 지켜야 한다. 공통 실행 행 잠금을 제거하므로 지원하지 않는 업무 테이블 외부 변경을 worker가 차단하지 않으며, 이를 최종 검산 실패로 탐지한다.

대안으로 공유 reader 기반 멀티스레드 Step은 reader 상태와 checkpoint 경쟁 때문에 선택하지 않았다. remote partitioning과 메시지 브로커는 별도 worker 운영과 전달 정합성 비용이 현재 범위를 넘으므로 선택하지 않았다. 판매자별 확정을 worker로 분할하는 방식은 부분 집계 저장·merge·재시작 계약이 추가되므로 PostgreSQL 단일 집합 연산의 성능 한계가 확인될 때 별도 결정으로 검토한다.
