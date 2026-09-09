# UCP Merchant Mock

로컬 및 개발 환경에서 외부 UCP Business 역할을 대신하는 시뮬레이터입니다.

Core가 UCP REST로 이 모듈을 호출하고, 결제가 필요하면 이 모듈이 `pay-service`를 호출하며, 주문이 생기면 Core로 Order Webhook을 보냅니다. MCP 서버, PG Mock, 모바일 앱과는 직접 통신하지 않습니다.

- `merchant-db`를 단독으로 소유합니다.
- 실제 Merchant 또는 운영 환경에서 사용하지 않습니다.
- Merchant 시나리오 재현에 필요한 최소 계약과 동작만 구현합니다.
- Flyway migration은 이 모듈의 `src/main/resources/db/migration/`에 둡니다.

설계 문서와 API 명세는 담당자(김호준)가 로컬에서 관리합니다. 팀에 공유가 필요한 계약과 결정은 레포 루트 `docs/`와 `docs/adr/`에 올립니다. 기능을 추가하거나 변경할 때는 먼저 문서를 갱신하고 그 내용을 구현합니다.

`backend/`에서 다음과 같이 검증합니다.

```bash
./gradlew :mocks:ucp-merchant-mock:test
./gradlew :mocks:ucp-merchant-mock:build
```
