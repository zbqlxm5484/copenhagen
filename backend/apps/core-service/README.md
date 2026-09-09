# Core Service

UCP 플랫폼의 공개 경계와 구매 흐름 조정을 담당하는 독립 애플리케이션입니다.

## 책임과 경계

- `core-db`를 단독으로 소유합니다.
- `pay-service`와 `ucp-merchant-mock`만 내부 HTTP로 호출할 수 있습니다.
- 서비스 간 데이터 구조는 `ucp-contract`와 `internal-api-contract`를 사용합니다.
- 다른 애플리케이션이 이 모듈의 구현 코드, Entity, Repository를 가져가면 안 됩니다.
- Pay 또는 Merchant의 DB에 직접 연결하면 안 됩니다.

## 주요 경로

```text
src/main/java/          애플리케이션 코드
src/main/resources/     설정과 Core 소유 Flyway migration
src/test/java/          Core 테스트
Dockerfile              독립 이미지 빌드 정의
```

## 개발 명령

`backend/`에서 실행합니다.

```bash
./gradlew :apps:core-service:test
./gradlew :apps:core-service:build
```

실행은 레포 루트에서 `docker compose up --build core-service` 또는 전체 `docker compose up --build`를 권장합니다. `bootRun`을 직접 사용할 때는 개발 PC에서 접근 가능한 PostgreSQL과 의존 서비스 URL을 환경변수로 별도 지정해야 합니다.

Docker Compose에서는 `CORE_DB_URL`, `PAY_SERVICE_URL`, `MERCHANT_SERVICE_URL` 등의 환경변수로 연결 대상을 주입합니다. 서비스 주소는 Nginx가 아니라 Docker DNS 이름을 사용합니다.

## Fraud 의도 분석 설정

`fraud.openai`는 기존 환경변수 placeholder와 `@ConfigurationPropertiesScan`으로 바인딩합니다.
`OPENAI_API_KEY`는 기본값 없이 비워 두고, `OPENAI_FRAUD_MODEL`은 `gpt-5.4-mini`,
`OPENAI_TIMEOUT_SECONDS`는 양수이며 기본 5초입니다. 설정 객체의 문자열 출력은 키를 가립니다.

루트 [.env.example](../../../.env.example)을 비추적 `.env`로 복사하면 Compose가 Core에 전달합니다.
`bootRun`은 `.env`를 자동으로 읽지 않으므로 같은 값을 실행 환경에 주입해야 합니다.
현재는 설정과 [공통 Fraud 데이터 계약](../../libs/internal-api-contract/README.md)만 있으며 OpenAI 호출은 없습니다.
