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

## 시연용 시나리오 스위치

Core·Pay 가 실패 상황을 잘 복구하는지 보이려면 이 모듈이 실패를 만들어 내야 합니다(설계서 12.5). 재기동 없이 켜고 끄는 내부 API 둘이 있습니다. Nginx 가 `/internal/` 을 막아 컨테이너 안에서만 닿습니다. 전역 하나이고 재기동하면 `NORMAL` 로 돌아옵니다.

```bash
# 지금 켜진 시나리오
curl -s http://localhost:8080/internal/v1/scenarios

# 정상으로 되돌리기
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"NORMAL"}'

# 다음 체크아웃 생성·수정부터 단가가 올라 CHECKOUT_PRICE_CHANGED 가 실린다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"PRICE_CHANGED"}'

# 모든 항목이 재고 부족. incomplete + CHECKOUT_STOCK_INSUFFICIENT
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"OUT_OF_STOCK"}'

# 배송 불가 지역. incomplete + ADDRESS_NOT_SERVICEABLE
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"ADDRESS_NOT_SERVICEABLE"}'

# API 로 해결 불가. 상태 requires_escalation + MERCHANT_HANDOFF_REQUIRED
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"REQUIRES_ESCALATION"}'

# 주문 웹훅을 같은 바이트·같은 서명으로 두 번 보낸다. Core Inbox 가 Webhook-Id 로 중복을 무시하는지 본다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"WEBHOOK_DUPLICATE"}'

# 주문 웹훅을 merchant.webhook.scenario-delay(기본 5초) 뒤에 보낸다. Core 가 주문 조회로 버티는지 본다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"WEBHOOK_DELAYED"}'
```

`INVALID_MERCHANT_SIGNATURE`, `COMPLETE_TIMEOUT_BEFORE_PROCESS`, `COMPLETE_TIMEOUT_AFTER_ORDER` 도 켜지지만 읽는 코드가 아직 없어 효과가 없습니다. 서명 발급(174)과 Complete(176)에서 붙입니다.

모르는 이름은 `400 INVALID_REQUEST` 입니다. compose 안에서는 `localhost:8080` 대신 `ucp-merchant-mock:8080` 으로 부릅니다.
