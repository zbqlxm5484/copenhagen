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

## 로컬 DB 통합 테스트 실행

`AuthSchemaIntegrationTest`처럼 실제 PostgreSQL 제약조건을 검증하는 테스트는 `CORE_TEST_DB_URL` 환경변수가
설정되어 있을 때만 실행되고, 없으면 자동으로 skip됩니다. CI(`core-test` job)에서는 항상 실행됩니다.

로컬에서 실행하려면 별도 PostgreSQL 컨테이너를 띄우고 접속 정보를 환경변수로 지정합니다.

```powershell
docker run --rm -d --name core-test-db -p 15432:5432 `
  -e POSTGRES_DB=core-test -e POSTGRES_USER=core -e POSTGRES_PASSWORD=core-dev-password `
  postgres:17-alpine

$env:CORE_TEST_DB_URL = "jdbc:postgresql://localhost:15432/core-test"
$env:CORE_TEST_DB_USER = "core"
$env:CORE_TEST_DB_PASSWORD = "core-dev-password"

.\gradlew.bat :apps:core-service:test
```

테스트가 끝나면 컨테이너를 정리합니다.

```powershell
docker stop core-test-db
```
