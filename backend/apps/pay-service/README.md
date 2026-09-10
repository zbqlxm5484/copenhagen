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

## 앱 결제수단 API 보안

PIN·생체인증·카드 결제수단 API는 `/api/v1` 사용자 인증 영역에서 제공합니다.

- PIN 원문·생체정보 원문·카드번호·CVC는 저장하거나 로그에 기록하지 않습니다.
- PIN은 Argon2id 검증값만 `payment_pin_credentials.pin_hash`에 저장합니다.
- Argon2id Salt는 해시 문자열에 포함되며, 별도 Salt 컬럼을 사용하지 않습니다.
- 배포 환경에서는 `PAY_PIN_PEPPER`를 Secret Manager 또는 환경변수로 주입합니다. Pepper는 Pay DB에 저장하지 않습니다.
- 카드 결제수단은 PG의 `provider_token_ref`만 저장하고, 삭제는 Soft Delete로 처리합니다.
