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
