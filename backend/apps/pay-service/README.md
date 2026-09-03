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
