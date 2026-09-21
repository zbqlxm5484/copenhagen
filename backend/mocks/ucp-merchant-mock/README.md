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

## 경로와 몰 세그먼트

컨테이너 하나가 가상 몰 셋을 서빙합니다. 어느 몰인지는 경로의 두 번째 세그먼트가 말합니다. 값은 `application.yml` 의 `merchant.malls[].id` 와 같고, Core 의 `services.merchants[].id`·Pay 토큰 JSON 의 키와 한 문자열이어야 합니다. 모르는 값은 `404 MERCHANT_NOT_FOUND` 입니다.

```
GET   /api/ucp/v1/{merchantId}/.well-known/ucp
POST  /api/ucp/v1/{merchantId}/catalog/search | lookup | product
POST  /api/ucp/v1/{merchantId}/checkout-sessions            GET · PUT · /complete · /cancel
GET   /api/ucp/v1/{merchantId}/orders/{orderId}
```

옛 경로 `/.well-known/ucp` 와 `/ucp/v1/**` 는 첫 몰(`merchant-a`)의 별칭으로 살아 있습니다. Core 가 `/ucp/v1/catalog/*` 를 base-url 뒤에 하드코딩하고 Pay 가 `/.well-known/ucp` 를 붙이기 때문이며, 둘이 몰별 주소로 옮기면 지웁니다. `/api/ucp/` 를 Nginx 가 이 컨테이너로 보내는 것은 인프라 몫입니다.

## 시연 카탈로그와 가상 몰 셋

이 모듈은 플랫폼 바깥의 독립 쇼핑몰을 흉내 냅니다. 시연에서는 컨테이너 하나 안에 가상 몰 셋(한강마켓 `merchant-a`, 노을상점 `merchant-b`, 별빛몰 `merchant-c`)이 들어가고 상품·체크아웃·주문이 `merchant_id` 컬럼으로 갈립니다. 세 몰이 상품 1,592건을 똑같이 취급하고, 같은 상품은 몰이 달라도 같은 `productId` 에 몰마다 다른 가격이라 Core 가 가격을 비교할 수 있습니다.

몰마다 다른 것은 둘이고 전부 결정적 해시로 정합니다. 가격(원본의 -10%~+10%, 10원·100원 단위 반올림)과 품절(약 5%)입니다. 난수를 쓰지 않아 스크립트를 다시 돌려도 같은 파일이 나옵니다. 취급 여부는 2026-09-18(428)에 뺐습니다 — 몰마다 상품이 달라 같은 검색어에 결과가 갈렸고, 그러면 가격 비교가 성립하지 않습니다.

원본에 옵션이 없는 상품(다나와·다이소)은 카테고리를 보고 옵션을 합성합니다. 신발은 사이즈 250~280 × 색상, 의류는 S·M·L, 색이 갈리는 카테고리(휴대폰·태블릿·노트북·가방 등)는 색상입니다. 가전·부품은 합성하지 않습니다. 몰당 상품 1,592건에 SKU 4,332개, 고를 옵션이 둘 이상인 상품이 847건입니다. 풀은 원본 1,408건과 시연용 식재료 수집본 184건을 합친 것입니다 — 원본에 마늘·두부·파마산 같은 기본 식재료가 없어 "재료 찾아서 장바구니에 담아줘" 시나리오가 성립하지 않았습니다(`tools/collect_kurly_grocery.py`). 카탈로그 응답에는 상품 이미지(`imageUrl`)가 함께 나갑니다.

```bash
# 카탈로그 풀 재생성(DESIGN.md 5.7절, ADR 0011). 원본 넷은 Git 에 없다 — 김지호 번들 둘과 컬리 수집본은 Downloads 에 있다
python tools/build_catalog_pool.py \
  --clean-sqlite ~/Downloads/korean_products_clean_bundle/korean_products_clean.sqlite \
  --raw-sqlite   ~/Downloads/korean_products_bundle/korean_products.sqlite \
  --kurly-catalog ~/Downloads/kurly-catalog-2026-09-21.json \
  --grocery tools/kurly-grocery.json \
  --out src/main/resources/catalog/pool.json.gz
# 규칙을 바꿨으면 황금값도 다시 뽑는다
python tools/mall_variation_golden.py --pool src/main/resources/catalog/pool.json.gz --out src/test/resources/catalog-pool/golden.json
```

컨테이너 하나가 몰 셋을 서빙하고(ADR 0008) 풀 파일 하나를 `MERCHANT_CATALOG_POOL_FILE` 로 받습니다. 몰별 가격·재고 편차는 로더가 기동 때 계산합니다(ADR 0011). `MERCHANT_CATALOG_POOL_LIMIT` 은 풀 앞 N 건만 싣는 값이고 0 이면 전량입니다. `MERCHANT_CATALOG_POOL_FILE` 을 비우면 V3 fixture 5건만 뜨고 테스트·CI 가 그 상태입니다.

compose 에서 b·c 는 profile `malls` 뒤에 있습니다. 기본 `up` 과 CI 스모크는 a 하나만 띄웁니다.

```bash
# 몰 셋 전부. merchant-db 볼륨이 이미 있으면 down -v 로 지운 뒤 올려야 b·c 데이터베이스가 만들어진다
docker compose --profile malls up -d
```

b·c 가 Core 검색에 잡히려면 Core `services.merchants[]` 와 Pay 토큰·`merchant_accounts` 에 같은 id 로 등록돼야 합니다. 그 전에는 떠 있어도 a 만 잡힙니다.

## 로그로 구매 하나 추적하기

로그 한 줄마다 `req= co= pay= ord= wh=` 다섯 칸이 붙습니다. `req` 는 `X-Request-Id` 입니다. Core 가 보낸 값이 있으면 그대로 쓰고 없으면 이 모듈이 만들어 응답 헤더로 돌려줍니다. 같은 값이 Pay 호출과 Order Webhook 의 `X-Request-Id` 헤더에 실리므로 세 서비스 로그를 한 값으로 이어 볼 수 있습니다. 워커가 집은 일은 `sweep-…`, `recheck-…`, `webhook-retry-…` 접두어의 requestId 를 새로 받습니다.

상태 전이는 `AUDIT` 접두어의 한 줄 JSON 으로 따로 남습니다. `event` 는 `checkout.created|updated|canceled|expired|signed|completing|completed|complete_reverted|complete_replayed`, `payment.started|finished|resolved|recheck_exhausted`, `order.created`, `webhook.enqueued|delivered|retry_scheduled|failed`, `scenario.changed` 입니다. `before`·`after` 가 상태 전후, `reason` 이 사유입니다.

```bash
# 요청 하나 따라가기
docker compose logs ucp-merchant-mock | grep 'req=core-req-42'

# 상태 전이만 보기
docker compose logs ucp-merchant-mock | grep ' AUDIT ' | grep '"checkoutId":"<checkoutId>"'
```

로그에 개인키, `credentialId`, 카드 정보, 상세 배송지는 남기지 않습니다. 감사 이벤트는 그런 이름의 키를 넣으려 하면 예외로 거절합니다.

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

# 다음 체크아웃부터 검증에 실패하는 Merchant Authorization 이 나간다. Pay 가 CHECKOUT_SIGNATURE_INVALID 로 거절하는지 본다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"INVALID_MERCHANT_SIGNATURE"}'

# Complete 가 결제 전에 merchant.checkout.scenario-delay(기본 10초) 만큼 멈춘다. Core 가 같은 키로 재시도하면 complete_in_progress 를 받는다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"COMPLETE_TIMEOUT_BEFORE_PROCESS"}'

# Complete 가 주문까지 만든 뒤 응답 직전에 멈춘다. Core 가 재결제 대신 체크아웃 조회(GET)로 completed 를 확인하는지 본다
curl -s -X POST http://localhost:8080/internal/v1/scenarios -H 'Content-Type: application/json' -d '{"scenario":"COMPLETE_TIMEOUT_AFTER_ORDER"}'
```

열 종류 전부 읽는 코드가 있습니다.

모르는 이름은 `400 INVALID_REQUEST` 입니다. compose 안에서는 `localhost:8080` 대신 `ucp-merchant-mock:8080` 으로 부릅니다.
