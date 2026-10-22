# ADR-037: 판매자 일일 정산의 수집 파티셔닝 제거와 정산 입력 상한 고정

- 상태: 승인
- 적용 영역: backend
- 결정일: 2026-10-22

## 결정

`dailySellerSettlementJob`에서 `settlement_entries`를 파티션별로 읽고 버리는 상세 수집 구간을 제거한다. 정산은 다음 두 단계로 구성한다.

```text
정산 실행 준비 + 대상 entry ID 상한 고정
→ 수집 집계 고정 (SQL 집계로 settlement_runs에 건수·금액 기록)
→ 판매자별 정산 확정 (settlement_entries → seller_settlements, SQL 집합 연산)
→ 판매자 지갑 입금 (seller_settlements 파티션 처리)
→ 정산 실행 완료
```

- 제거 대상은 collection 파티션 계획 준비 Step, collection manager·worker Step, collection `Partitioner`, `SettlementPaymentKeysetReader`, `SettlementPaymentProcessor`, `SettlementPaymentProjection`와 collection 전용 설정(`collection-partition-count`)이다.
- `settlement_entries`의 최소·최대 ID는 정산 실행 준비 단계에서 한 번 조회해 Job `ExecutionContext`에 고정한다. 같은 JobInstance 재시작에서는 저장된 값을 재사용하고 현재 테이블 상태로 다시 계산하지 않는다. 수집 집계 고정과 판매자별 확정은 이 상한과 정산일 안의 entry만 대상으로 한다.
- `completeSettlementCollectionStep`은 유지한다. 상한 안의 entry 건수·금액을 `settlement_runs`에 기록하고 `COLLECTED`로 전이하는 대사 기준점이며, 판매자별 확정의 검산이 이 값을 사용한다.
- 판매자별 확정, 지갑 입금, 원장 불변식, credit 파티셔닝과 `IdPartitionPlanService`는 이번 결정에서 변경하지 않는다. credit 계획 저장 방식의 단순화는 별도 결정으로 다룬다.

이 결정은 [ADR-035](ADR-035-payment-approval-settlement-entry-snapshot.md)가 유지하도록 한 ADR-030의 "collection의 local partitioning과 worker별 checkpoint" 부분을 대체한다. ADR-030의 credit 파티셔닝, 결정론적 계획과 지갑 입금 재시작 계약은 유지한다.

## 이유

ADR-035 이후 결제별 정산 근거는 승인 transaction이 생성한 불변 `settlement_entries`다. 주문 상태, 금액 일치, 판매·지급 대상 검증은 승인 시점에 끝나므로 배치가 항목별로 읽어 변환하고 저장할 대상이 없다. 그런데 ADR-035가 입력만 `settlement_entries`로 바꾸고 기존 collection partitioning을 유지하도록 정해, 현재 구현에는 다음 잔재가 남아 있다.

- collection worker의 writer가 `ItemWriter { }`로 아무것도 쓰지 않는다. 대상 entry를 전부 읽고 버린다.
- 수집 건수·금액은 `aggregateEntries`가, 판매자별 집계는 `createTemporarySellerAggregates`가 `settlement_entries`를 SQL로 직접 다시 집계한다. collection worker의 읽기 결과를 소비하는 곳이 없다.
- `SettlementPaymentProcessor`는 main 코드에서 사용되지 않고 테스트에서만 참조된다. reader SQL은 `'PAID' AS order_status`를 상수로 반환한다.
- collection 파티션 계획이 Job `ExecutionContext`에 남기는 유효한 산출물은 `collection.partition.minId/maxId`뿐이며, 이는 대상 상한 고정이라는 별도 요구를 충족하는 값이다.

따라서 업무적으로 필요한 것은 (1) 최초 실행에서의 대상 상한 고정, (2) 건수·금액 대사 기준점, (3) 판매자별 정산 확정, (4) 입금이다. 읽기 파이프라인은 이 중 어느 것에도 기여하지 않으면서 1억 건 규모에서 I/O와 DB 부하, 파티션 계획·checkpoint 복원 코드와 성능 검증 항목을 유지하는 비용만 만든다.

검토한 대안은 다음과 같다.

- **현상 유지:** 변경 비용은 없지만 아무 일도 하지 않는 단계를 계속 유지·측정·테스트해야 하고, 후속 개발자가 이 단계에 의미가 있다고 오해한다.
- **collection worker가 상세를 `settlement_details`에 복제:** ADR-035가 이미 거부한 방식이다. 결제별 근거는 `settlement_entries`가 담당하므로 같은 내용을 다시 쓰지 않는다.
- **확정과 입금을 하나의 Step으로 통합:** 입금 worker가 판매자별 합계를 직접 만들려면 판매자 키로 entries를 다시 읽거나 파티션별 부분 집계를 영구 저장·merge해야 한다. 입금 멱등성과 원장 `(source_type, source_id)` 기준이 되는 확정 금액의 고정본, 그리고 `entries = seller_settlements = ledger` 단계별 대사도 약해진다. ADR-030과 파티션 명세가 부분 집계를 측정 전에는 도입하지 않기로 한 판단과도 일치하지 않는다.
- **ID 상한을 `settlement_runs` 컬럼으로 저장:** 상한이 업무 데이터로 남아 관측성은 좋아지지만 migration이 필요하다. Job `ExecutionContext` 저장만으로 재시작 요구를 충족하므로 선택하지 않는다.

## 트레이드오프

- reader가 수행하던 범위 이탈·cursor 증가 검사가 사라진다. 대신 집계와 확정이 동일한 정산일·ID 상한 조건의 SQL을 사용하므로 같은 경계가 DB에서 보장되며, 건수·금액 대사가 누락과 중복을 탐지한다.
- 대상 상한 고정이 준비 단계로 옮겨진다. 상한 조회와 저장의 재시작 멱등성(이미 저장된 상한 재사용, 일부 key만 존재하면 실패)을 준비 단계에서 직접 보장해야 한다.
- 관련 명세와 성능 검증 기준을 함께 갱신해야 한다. 대상은 파티션 명세의 상세 수집 파티셔닝 절, 스냅샷 명세의 "기존 local partitioning 유지" 서술, 성능 harness의 collection 파티션 편향·카운터·SLA 항목, `DailySellerSettlementJobIntegrationTest`의 collection 파티션 시나리오다. 단계별 SLA 표(entry 조회·수집 검산·확정 20분)에서 읽기 구간이 차지하던 비중은 아직 측정하지 않았으므로, 구현 후 성능 시나리오로 다시 확인한다.
- Step 구성이 바뀌므로 변경 이전 구조로 시작된 미완료 JobInstance는 새 구조로 재시작할 수 없을 수 있다. 이 Job은 아직 운영 정산에 사용하지 않으므로 허용하며, 배포 전 미완료 JobInstance가 없음을 확인한다.
- `collection-partition-count` 설정을 제거하므로 해당 property를 전달하는 성능 테스트 실행 명령과 문서를 함께 정리해야 한다.
