# PG Mock

로컬 및 개발 환경에서 외부 결제 게이트웨이 역할을 대신하는 시뮬레이터입니다.

- DB를 소유하지 않으며 다른 애플리케이션을 호출하지 않습니다.
- 실제 결제 시스템이나 운영 환경에서 사용하지 않습니다.
- Pay 연동을 재현하는 데 필요한 최소 계약과 동작만 구현합니다.
- 제품 비즈니스 규칙을 이 모듈에 넣지 않습니다.

`backend/`에서 다음과 같이 검증합니다.

```bash
./gradlew :mocks:pg-mock:test
./gradlew :mocks:pg-mock:build
```

Pay의 `POST /internal/v1/charges` 호출에 대한 결과는 환경변수로 선택합니다.

```text
PG_MOCK_OUTCOME=SUCCESS
PG_MOCK_OUTCOME=FAILED
PG_MOCK_OUTCOME=TIMEOUT
```

`TIMEOUT`은 `PG_MOCK_RESPONSE_DELAY_MILLIS`만큼 응답을 지연합니다. 값을 생략하면
Pay의 요청 타임아웃을 초과하도록 5초 지연을 사용합니다. Mock은 결제 원장이나 카드정보를
저장하지 않습니다.

## 기존 거래 조회

`GET /internal/v1/charges?pgTransactionId=...` 또는 `?pgRequestId=...`로 기존 거래를 조회합니다.
Mock은 접수한 요청 식별자와 결과만 메모리에 보관하며 카드 토큰·결제 요청 원문은 보관하지 않습니다.
재시작하면 사라지므로 미발견 응답은 Pay에서 UNKNOWN으로 취급합니다.

`PG_MOCK_OUTCOME=TIMEOUT`일 때 조회 결과는 `PG_MOCK_LOOKUP_OUTCOME`으로 지정합니다.
`SUCCESS`, `FAILED`, `UNKNOWN`을 지원하며 기본값은 `UNKNOWN`입니다. 조회는 새 거래를 만들지 않습니다.
