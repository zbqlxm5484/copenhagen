# Pay Service

AP2와 유사한 권한 부여, mandate, credential, 결제 처리를 담당하는 독립 애플리케이션입니다.

## 책임과 경계

- `pay-db`를 단독으로 소유합니다.
- 외부 결제 처리는 `pg-mock`을 내부 HTTP로 호출합니다.
- Core 또는 Merchant의 DB에 직접 연결하면 안 됩니다.
- 결제 관련 데이터 구조는 `payment-handler-contract`와 `internal-api-contract`를 사용합니다.
- 다른 애플리케이션이 이 모듈의 구현 코드, Entity, Repository를 가져가면 안 됩니다.

## 주요 경로

```text
src/main/java/          애플리케이션 코드
src/main/resources/     설정과 Pay 소유 Flyway migration
src/test/java/          Pay 테스트
Dockerfile              독립 이미지 빌드 정의
```

## 개발 명령

`backend/`에서 실행합니다.

```bash
./gradlew :apps:pay-service:test
./gradlew :apps:pay-service:build
```

실행은 레포 루트에서 `docker compose up --build pay-service` 또는 전체 `docker compose up --build`를 권장합니다. `bootRun`을 직접 사용할 때는 개발 PC에서 접근 가능한 PostgreSQL과 PG URL을 환경변수로 별도 지정해야 합니다.

Docker Compose에서는 `PAY_DB_URL`, `PG_SERVICE_URL` 등의 환경변수로 연결 대상을 주입합니다. PG 주소는 Docker DNS 이름을 사용합니다.

결제 실행은 `PG_SERVICE_URL`의 PG Adapter를 호출하며, 응답을 받지 못하면 재호출하지 않고
Payment를 `UNKNOWN`으로 기록합니다. 연결 타임아웃은 `PG_CONNECT_TIMEOUT_MILLIS`, 요청 타임아웃은
`PG_REQUEST_TIMEOUT_MILLIS`로 조정할 수 있습니다.

로컬 PG Mock 시나리오는 다음처럼 선택할 수 있습니다.

```text
PG_MOCK_OUTCOME=SUCCESS  # 승인
PG_MOCK_OUTCOME=FAILED   # 거절
PG_MOCK_OUTCOME=TIMEOUT  # 응답 지연·유실 → Pay UNKNOWN
```

## UNKNOWN 대사 Worker

`PayWorker`가 `payment_reconciliation_jobs`를 주기적으로 처리합니다. 별도 대사 HTTP API는 없습니다.
기존 PG 거래 ID 또는 요청 ID로 상태만 조회하며, 새 결제·CHARGE Attempt를 생성하지 않습니다.
확정 결과와 Receipt·승인 세션·Job 완료는 한 트랜잭션으로 반영합니다.

| 환경변수 | 기본값 | 용도 |
| --- | --- | --- |
| `PAY_RECONCILIATION_ENABLED` | `true` | Worker 실행 여부 |
| `PAY_RECONCILIATION_POLL_MILLIS` | `5000` | 배치 종료 후 다음 실행까지 대기 |
| `PAY_RECONCILIATION_BATCH_SIZE` | `10` | 배치당 처리할 최대 Job 수 |
| `PAY_RECONCILIATION_LEASE_SECONDS` | `60` | Worker 선점 유효 시간 |
| `PAY_RECONCILIATION_RETRY_SECONDS` | `60` | 미확정 조회 후 재시도 대기 |

조회 한도는 설정으로 늘릴 수 없는 5회입니다. lease는 PG 요청 타임아웃보다 충분히 길게 설정합니다.
선점 시 조회 횟수를 예약하므로 Worker가 조회 직전에 중단되어도 해당 시도는 소비됩니다.
lease 만료 Job은 회수되며, 마지막 시도 중 중단된 Job은 추가 조회 없이 EXHAUSTED로 종료합니다.
EXHAUSTED의 Payment는 UNKNOWN을 유지하고 운영자의 수동 확인 대상으로 남습니다.
Worker를 여러 인스턴스에서 실행해도 같은 Payment의 유효한 선점은 하나만 허용합니다.

PG Mock에서는 `PG_MOCK_OUTCOME=TIMEOUT`과 `PG_MOCK_LOOKUP_OUTCOME=SUCCESS|FAILED|UNKNOWN`으로
대사 시나리오를 재현할 수 있습니다. 자세한 기준은 [대사 설계](docs/unknown-reconciliation.md)를 참고합니다.

## 앱 결제수단 API 보안

PIN·생체인증·카드 결제수단 API는 `/api/pay/v1` 사용자 인증 영역에서 제공합니다.

- PIN 원문·생체정보 원문·카드번호·CVC는 저장하거나 로그에 기록하지 않습니다.
- PIN은 Argon2id 검증값만 `payment_pin_credentials.pin_hash`에 저장합니다.
- Argon2id Salt는 해시 문자열에 포함되며, 별도 Salt 컬럼을 사용하지 않습니다.
- 배포 환경에서는 `PAY_PIN_PEPPER`를 Secret Manager 또는 환경변수로 주입합니다. Pepper는 Pay DB에 저장하지 않습니다.
- 카드 결제수단은 PG의 `provider_token_ref`만 저장하고, 삭제는 Soft Delete로 처리합니다.

## 서비스 간 인증

Pay는 경로별 호출 주체를 구분합니다.

| 경로 | 허용 주체 |
| --- | --- |
| `/api/pay/v1/**` | 사용자 Access Token |
| `/internal/v1/**` | `core-service` 서비스 토큰 |
| `/merchant/v1/**` | 활성 Merchant에 등록된 Merchant 서비스 토큰 |

운영 사용자 토큰은 `PAY_AUTH_JWT_*` 환경변수로 주입한 Core 공개 JWK를 사용해 검증합니다.
로컬 정적 토큰은 테스트·개발 용도로만 `PAY_AUTH_USER_TOKENS_JSON`,
`PAY_AUTH_CORE_SERVICE_TOKENS_JSON`, `PAY_AUTH_MERCHANT_SERVICE_TOKENS_JSON`에 설정할 수
있습니다. JSON은 `주체 식별자 -> 토큰` 형식이며, 실제 값은 저장소에 커밋하지 않습니다.

Merchant 서비스 토큰의 주체는 Merchant `merchantId`와 같아야 하며, Pay DB의 활성
`merchant_accounts`에 등록된 Merchant만 Merchant 경로를 호출할 수 있습니다. 인증 토큰과
`Authorization` 헤더는 로그에 기록하지 않습니다.

## Merchant Checkout 서명 검증

승인 세션을 만들기 전에 Pay는 Merchant의 공식 UCP Profile에서 Checkout 검증 공개키를 조회합니다.

```text
GET ${MERCHANT_SERVICE_URL}/.well-known/ucp
```

Profile의 `keys[]`에 포함된 공개 JWK 중 Compact JWS Header의 `kid`와 일치하는 활성 서명키만 사용합니다.
허용 알고리즘, `kid`, Merchant 서명, `merchantId`, `checkoutId`, `purchaseRequestId`, `amountMinor`,
`currency`, `checkoutHash`를 검증한 뒤 성공한 경우에만 승인 세션과
`pay_verified_checkout_evidence`를 같은 트랜잭션으로 저장합니다. 공개키 조회 장애와 서명·Claim 검증 실패는
서로 다른 오류로 반환합니다.

| 환경변수 | 기본값 | 용도 |
| --- | --- | --- |
| `MERCHANT_SERVICE_URL` | `http://ucp-merchant-mock:8080` | Merchant UCP Profile base URL |
| `PAY_MERCHANT_PROFILE_PATH` | `/.well-known/ucp` | 공개키 Profile 경로 |
| `PAY_MERCHANT_CHECKOUT_ALLOWED_ALGORITHMS` | `ES256` | 허용할 JWS 알고리즘 목록(CSV) |
| `PAY_MERCHANT_CONNECT_TIMEOUT_MILLIS` | `1000` | 공개키 조회 연결 타임아웃 |
| `PAY_MERCHANT_REQUEST_TIMEOUT_MILLIS` | `3000` | 공개키 조회 요청 타임아웃 |

Profile 응답의 공개키와 Checkout JWS 원문은 로그에 기록하지 않습니다. Merchant Profile 주소와 운영 Secret은 배포
환경에서 주입하며 저장소에 커밋하지 않습니다.
