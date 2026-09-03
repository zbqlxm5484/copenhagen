# MCP Server

Agent 요청을 Core 내부 API 계약으로 변환하는 MCP 어댑터입니다.

## 책임과 경계

- DB를 소유하지 않습니다.
- 애플리케이션 중에서는 `core-service`만 내부 HTTP로 호출합니다.
- Pay, Merchant, PG를 직접 호출하면 안 됩니다.
- Core 구현 모듈을 의존하지 않고 `internal-api-contract`의 계약만 사용합니다.
- 도메인 규칙은 MCP에 구현하지 않고 책임 서비스에 둡니다.

## 주요 경로

```text
src/main/java/          애플리케이션과 MCP 어댑터 코드
src/main/resources/     실행 설정
src/test/java/          MCP 테스트
Dockerfile              독립 이미지 빌드 정의
```

## 개발 명령

`backend/`에서 실행합니다.

```bash
./gradlew :apps:mcp-server:test
./gradlew :apps:mcp-server:build
```

실행은 레포 루트에서 `docker compose up --build mcp-server` 또는 전체 `docker compose up --build`를 권장합니다. Core 주소는 `CORE_SERVICE_URL` 환경변수로 주입하며 Docker Compose 기본값은 `http://core-service:8080`입니다.
