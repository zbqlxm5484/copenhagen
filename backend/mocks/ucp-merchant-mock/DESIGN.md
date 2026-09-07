# UCP Merchant Mock 설계

이 문서는 `ucp-merchant-mock` 모듈의 설계 관점 Source of Truth다. 코드는 이 문서를 구현한 산출물이다.

기능을 추가하거나 바꿀 때는 코드를 먼저 건드리지 않는다. 이 문서를 고치고 리뷰받은 뒤 그 내용을 구현한다. 구현 중 설계와 다른 판단이 필요해지면 코드에 넣지 말고 이 문서를 먼저 고친다.

근거는 프로젝트 설계서 v2.1(2026-09-02)이다. 절 번호를 괄호로 적었다. 이 문서와 설계서가 어긋나면 설계서가 우선하고, 어긋난 사실을 아래 "미확정과 합의 대기"에 적는다.

담당 김호준. 마지막 갱신 2026-09-07.

---

## 1. 이 모듈이 하는 일

외부 UCP Business, 즉 우리 플랫폼 바깥의 쇼핑몰을 모의 구현한다. 상품·장바구니·체크아웃·주문의 원본을 소유하고 실제로 결제를 시작하는 주체다.

단순 고정 응답 스텁이 아니다(12.5). 전체 구매 흐름과 실패 복구를 증명하는 시연 구성요소다. 가격 변경, 재고 부족, Complete 응답 유실, 웹훅 중복 같은 상황을 실제로 만들어 낼 수 있어야 Core와 Pay의 복구 로직을 검증할 수 있다.

통신 상대는 둘뿐이다.

```
Core ──UCP REST 14개──▶ Merchant Mock ──결제 청구──▶ Pay
  ▲
  └──────── Order Webhook (RFC 9421 서명) ─────────────┘
```

MCP 서버, PG Mock, 모바일 앱과는 직접 통신하지 않는다. 사용자 계정, 카드, PIN, 생체인증, 승인 세션, Agent OAuth는 이 모듈의 책임이 아니다.

전체 구매 19단계 중 이 모듈이 직접 수행하는 것은 2(Profile 노출), 4(Catalog), 5(Cart), 6~7(Checkout 확정과 서명), 12~14(Complete 수신, Mandate 검증, Pay 청구), 17(Order 생성), 18(웹훅 발송)이다.

---

## 2. 절대 깨지면 안 되는 불변 조건

이 목록이 이 모듈의 존재 이유다. 나머지는 평범한 CRUD다.

**결제와 주문의 단일성**

1. 주문은 Pay가 `SUCCESS`를 반환한 뒤에만 생성한다. `UNKNOWN`이면 주문을 만들지 않고 재청구도 하지 않는다(단계 16, 17).
2. 하나의 체크아웃에서 결제는 논리적으로 한 번만 일어난다. Pay 멱등 키는 `merchant-checkout-{checkoutId}` 하나로 고정하며 타임아웃 재시도에도 바꾸지 않는다(17.5).
3. `orders.checkout_session_id`의 UNIQUE 제약이 중복 주문의 최종 방어선이다. 멱등 로직에 버그가 있어도 DB가 막는다. INSERT가 UNIQUE 위반으로 튕기면 기존 주문을 조회해 반환한다.
4. Complete를 같은 멱등 키로 다시 호출하면 결제하지 않고 저장된 응답을 그대로 재생한다. 같은 키에 다른 바디가 오면 `IDEMPOTENCY_KEY_CONFLICT`로 거절한다.

**서명 바이트의 안정성**

5. `checkout_sessions.canonical_checkout`은 JCS 정규화 결과 문자열을 그대로 보관한다. 검증할 때 다시 정규화하지 않는다. 재정규화하면 바이트가 흔들려 간헐적으로 검증이 실패한다.
6. `checkout_sessions.merchant_authorization_jws`는 발급한 값을 그대로 보관한다. ECDSA 서명은 결정적이지 않아 같은 입력을 다시 서명해도 값이 달라진다.
7. 웹훅 서명은 반대로 발송할 때마다 새로 만든다. RFC 9421 서명에는 `created` 시각이 들어가고 수신 측이 오래된 서명을 거부하는 것이 일반적이라, 저장분을 재사용하면 지연 재전송이 만료로 튕긴다. `webhook_deliveries`의 서명 컬럼은 마지막 발송 기록용이다.
8. `webhook_deliveries.webhook_id`는 Core가 중복을 판별하는 키다. 재전송할 때 새로 만들면 Core가 새 주문으로 오인한다. `payload_json`도 같이 고정한다.

**검증 우선**

9. Checkout Mandate 검증 8항목 중 하나라도 실패하면 Pay를 호출하지 않고 거절한다. 결제 시도 자체가 없어야 한다(단계 13).
10. 값이 없으면 키를 생략한다. `null`을 넣지 않는다. 다이제스트가 달라진다.
11. 금액은 minor unit 정수다. `double`이나 부동소수를 쓰지 않는다.

---

## 3. 아키텍처

### 3.1 패키지 구조

기준 패키지는 `com.copenhagen.merchantmock`. 설계서 12.5의 패키지 구조를 따른다.

```
com.copenhagen.merchantmock
├── profile/        Business Profile(/.well-known/ucp), 서명키 로딩과 공개
├── catalog/        Catalog Search·Lookup·Product
├── inventory/      재고 조회. MVP는 차감하지 않는다
├── cart/           Cart Create·Get·Update·Cancel
├── checkout/       Checkout Create·Get·Update·Complete·Cancel, 상태 전이, 금액 재계산
├── security/       JCS 정규화, checkoutDigest, ES256 Detached JWS 서명·검증, RFC 9421
├── paymenthandler/ Pay Processor 호출 클라이언트, 결제 상태 해석
├── order/          Order 생성과 조회
├── webhook/        웹훅 발송 큐, 서명, 재전송 워커
├── idempotency/    멱등 키 저장과 재생
├── scenario/       시나리오 제어 API와 인메모리 상태
└── support/        오류 Envelope, 공통 응답, 시간·금액 유틸
```

`security/`의 JCS와 HTTP Signature 유틸, 오류 Envelope은 Core도 같은 것이 필요하다. 중복 구현이 확인되면 `libs/`로 올린다(21.1). 엔티티, 리포지토리, 체크아웃 도메인, 상태 전이는 어떤 경우에도 공유하지 않는다(21.2).

### 3.2 요청이 흐르는 경로

Controller는 HTTP와 DTO 변환만 한다. 비즈니스 판단은 Service, SQL은 Repository에 둔다. 검증 경로에는 조인이 없다.

```
Controller  →  Service  →  Repository (JdbcTemplate)
   │             │
   │             └→ security/  (정규화·서명·검증)
   │             └→ paymenthandler/  (Pay 호출)
   └→ support/ 오류 Envelope
```

Complete 요청은 이 흐름이 가장 길다. 순서를 고정한다.

```
1. 멱등 키 조회. 같은 키 + 같은 바디면 저장된 응답 재생하고 종료
2. 같은 키 + 다른 바디면 409 IDEMPOTENCY_KEY_CONFLICT
3. checkout_sessions를 SELECT 한 번으로 읽는다. 조인 없음
4. 상태가 ready_for_complete인지, 만료되지 않았는지 확인
5. Checkout Mandate 검증 8항목. 하나라도 실패하면 여기서 거절. Pay 호출 없음
6. 상태를 complete_in_progress로 조건부 UPDATE
7. payment_status = REQUESTED로 기록하고 Pay 호출
8. SUCCESS면 Order 생성 → completed → 응답 저장 → 웹훅 큐 적재
   FAILED면 ready_for_complete로 되돌리고 오류 반환
   UNKNOWN이면 주문을 만들지 않고 워커에 맡긴다
```

5번과 7번 사이에 다른 I/O를 넣지 않는다. 검증이 끝난 시점과 결제가 시작되는 시점 사이가 길어질수록 상태가 어긋날 여지가 생긴다.

---

## 4. 데이터 접근 방식

### 결정: JdbcTemplate

JPA를 쓰지 않는다. `spring-boot-starter-jdbc`만 쓴다. 리포 골격에 이미 이 의존성만 들어 있어 추가 변경이 없다.

**근거**

테이블이 5개고 검증 경로에 조인이 0개다. JPA가 해결해 주는 문제인 연관 관계 탐색과 지연 로딩이 여기에는 없다.

핵심 컬럼 다수가 JSONB다. `variants_json`, `items_json`, `messages_json`, `fulfillment_address`, `completion_response_json`, `raw_order_json`, `payload_json` 일곱 개다. JPA로 JSONB를 다루려면 컨버터나 hypersistence-utils 같은 추가 의존성이 필요하고, 그렇게 붙여도 결국 문자열을 직접 다루게 된다.

가장 결정적인 이유는 불변 조건 5·6번이다. `canonical_checkout`과 `merchant_authorization_jws`는 한 번 쓰면 절대 다시 계산되면 안 되는 값이다. JPA의 더티 체킹은 어떤 필드가 언제 UPDATE에 포함되는지를 프레임워크가 판단한다. 이 두 컬럼에 대해서는 그 판단을 프레임워크에 맡기고 싶지 않다. SQL을 직접 쓰면 무엇을 쓰고 무엇을 안 쓰는지가 코드에 그대로 보인다.

조건부 UPDATE를 여러 곳에서 쓴다. 결제 상태 갱신, 체크아웃 상태 전이, 웹훅 상태 전이가 전부 `WHERE 현재상태 = ?` 형태다. 영향받은 행 수로 경합을 판정한다. JdbcTemplate은 이것을 그대로 표현한다.

**트레이드오프**

SQL을 손으로 쓰므로 컬럼을 추가할 때 INSERT와 SELECT 두 곳을 같이 고쳐야 한다. 컴파일 타임에 안 잡힌다. 이건 테이블 5개 규모에서 감수할 만한 비용이다. Repository 단위 테스트로 막는다.

낙관적 잠금을 프레임워크가 안 해 주므로 `version` 컬럼 증가를 SQL에 직접 쓴다.

**구현 규칙**

JSONB 컬럼에 바인딩할 때 PostgreSQL은 명시적 캐스팅을 요구한다. `?::jsonb`를 쓰거나 `PGobject`를 쓴다. 프로젝트 전체에서 하나로 통일하고 `support/`에 헬퍼를 둔다.

Enum은 DB에 대문자 문자열로 저장하고 API 응답에서 소문자 snake로 변환한다(7절 참고). PostgreSQL의 enum 타입 대신 `varchar` + 애플리케이션 검증을 쓴다. enum 타입은 값을 추가할 때 마이그레이션이 번거롭다.

읽기 전용 조회와 쓰기를 섞지 않는다. 트랜잭션 경계는 Service 메서드에 둔다.

---

## 5. 데이터 모델

설계서 15.3은 17개 테이블을 제시한다. 5개로 줄였다.

**축소 근거와 트레이드오프**

목 서버의 실제 부하는 데모와 통합 테스트 수준이다. 상품 수십 개, 동시 사용자 한 자릿수다. 이 규모에서 정규화가 주는 이득인 저장 공간 절약과 갱신 이상 방지는 거의 없다. 반면 테이블 하나당 드는 비용은 확실하다. 리포지토리, 마이그레이션, 매핑 코드, 테스트가 각각 늘어난다.

포기하는 것은 항목 단위 쿼리와 부분 갱신이다. `cart_items`를 JSONB로 흡수했으므로 "특정 SKU가 담긴 장바구니 찾기" 같은 쿼리를 못 한다. 그런 경로가 이 모듈에 없다. UCP Cart Update는 전체 교체 시맨틱이라 애초에 항목별 연산이 존재하지 않는다.

만약 상품 수가 수천 개로 늘거나 카탈로그 검색에 필터가 붙으면 `products`부터 정규화를 다시 검토한다. 그 시점의 신호는 카탈로그 응답 시간이다.

### 5.1 테이블

```
products            상품 + 옵션 + 재고
carts               장바구니
checkout_sessions   체크아웃 + 서명 + 멱등 + 결제
orders              주문
webhook_deliveries  웹훅 발송 큐 겸 이력
```

DBML 원본은 `schema.dbml`, 렌더링한 ERD는 `erd.png`로 이 문서 옆에 둔다. 컬럼 주석에 개별 설계 이유가 있다. `merchant-db`는 이 모듈만 소유하므로 스키마 산출물도 모듈 안에 둔다. `erd.png`는 dbdiagram.io로 `schema.dbml`을 렌더링한 것이라 DBML을 고치면 같이 갱신한다.

**products**

`id`, `external_id`(UCP 응답에 노출되는 상품 코드, UNIQUE), `title`, `image_url`, `base_price`(minor unit), `currency`, `status`, `variants_json`, 타임스탬프.

옵션과 재고를 `variants_json`으로 흡수했다. 형태는 `[{sku, optionName, optionValue, priceDelta, stock, status}]`. 상품 수십 개 규모라 전량 메모리 로드가 조인보다 싸다. 기동 시 SKU를 키로 Map에 올린다.

MVP는 재고를 차감하지 않는다. 품절은 시나리오 API로 `stock`을 0으로 주입해 만든다. 실제 차감을 넣으면 동시성 처리가 필요한데 그건 이 목 서버가 증명하려는 것이 아니다.

`seller_name`은 머천트가 하나뿐이라 설정에서 읽는다.

**carts**

`id`, `platform_ref`(Core가 준 멱등 키, UNIQUE), `status`, `currency`, `items_json`, `expires_at`, 타임스탬프.

`items_json`은 `[{sku, quantity, unitPrice}]`. 소계는 저장하지 않고 매번 계산한다. 저장하면 두 값이 어긋날 수 있고, 어느 쪽이 맞는지 판단하는 코드가 추가로 필요해진다.

만료 워커를 두지 않는다. PUT과 GET 처리 시점에 `expires_at`을 검사해 410을 반환한다. 배치를 하나 줄이는 대신 만료된 행이 DB에 남는데, 데모 규모에서 문제가 안 된다.

`platform_ref`는 Core가 `cart-create-{commerceSessionId}`를 실제로 보내는지에 달렸다. 안 보내면 이 컬럼을 삭제한다(11절).

**checkout_sessions**

컬럼이 25개로 가장 크다. 체크아웃, 서명, 멱등, 결제 네 관심사를 한 행에 담았다. 목적은 Complete 검증이 SELECT 한 번으로 끝나는 것이다. 검증 경로에 조인이 0개여야 한다.

```
정체성   id(= Mandate의 business_checkout_id), cart_id, buyer_ref, status
금액     currency, total_amount(빠른 비교용 캐시), fulfillment_address, messages_json
서명     canonical_checkout, checkout_digest, merchant_authorization_jws, signing_kid, expires_at
멱등     completion_key(UNIQUE), completion_request_hash, completion_response_json
결제     payment_status, pay_payment_id, processor_transaction_id, payment_error_code,
         payment_attempt_count, payment_requested_at
동시성   version
```

`total_amount`는 캐시다. `canonical_checkout` 안의 값과 어긋나면 `canonical_checkout`이 진실이다.

`signing_kid`를 따로 저장하는 이유는 키를 교체한 뒤에도 옛 체크아웃을 검증할 수 있어야 하기 때문이다.

`payment_attempt_count`는 조회 시도 횟수지 재청구 횟수가 아니다. 재청구는 하지 않는다.

저장하지 않는 것. `payment_key`는 `"merchant-checkout-" + id`로 항상 계산된다. `signed_at`은 `expires_at - 5분`이다. `credential_token_hash`는 아무도 읽지 않는다. 중복은 `completion_key`가 막고 재사용은 Pay가 막는다.

인덱스는 `(status, expires_at)`과 `(payment_status, payment_requested_at)` 둘. 각각 만료 스윕 워커와 UNKNOWN 재조회 워커가 스캔하는 경로다. `checkout_digest`로 조회하는 경로는 없어서 인덱스를 두지 않는다.

**orders**

`id`(Core에 전달되는 주문번호), `checkout_session_id`(UNIQUE), `status`, `total_amount`, `currency`, `raw_order_json`, `version`, 타임스탬프.

`raw_order_json`이 주문 조회 응답이자 웹훅 본문의 원본이다. `buyer_ref`를 포함한다. `order_items`가 없는 이유는 여기에 스냅샷이 이미 있기 때문이다. `fulfillment_events`도 없다. 현재 상태는 `status`, 이력은 `webhook_deliveries`가 가진다.

`version`이 필요한 이유는 주문 상태가 CREATED 이후에도 여러 번 바뀌기 때문이다.

**webhook_deliveries**

`webhook_id`(PK), `order_id`, `event_type`, `payload_json`, `signature_input`, `signature`, `content_digest`, `status`, `attempt_count`, `next_attempt_at`, `response_status`, `created_at`.

주문 상태가 여러 번 바뀔 수 있어 `orders`와 1:N이다. 컬럼으로 흡수할 수 없는 유일한 테이블이다.

`webhook_id`를 PK로 겸한다. 별도 `id`를 두면 "Core에 보낸 식별자"와 "내 행 식별자"가 갈라지는데 그럴 이유가 없다.

`target_url`은 Core가 하나뿐이라 설정에서 읽는다.

인덱스는 `(status, next_attempt_at)`과 `order_id`.

### 5.2 참조 관계

```
checkout_sessions.cart_id       →  carts.id
orders.checkout_session_id      —  checkout_sessions.id  (1:1)
webhook_deliveries.order_id     →  orders.id
```

`products`와 `carts`·`checkout_sessions` 사이에는 FK가 없다. `items_json`과 `canonical_checkout` 안의 line item이 `variants_json`의 SKU를 문자열로 참조한다.

FK를 걸지 않는 것은 실수가 아니라 설계다. 체크아웃 이후의 상품 정보는 참조가 아니라 서명된 스냅샷이어야 한다. 상품 가격이 나중에 바뀌어도 이미 서명한 체크아웃의 금액은 변하면 안 된다. FK로 묶으면 "지금의 상품"을 가리키게 되어 오히려 틀린 모델이 된다.

### 5.3 마이그레이션

`backend/mocks/ucp-merchant-mock/src/main/resources/db/migration/`에만 둔다. 다른 서비스의 DB를 건드리지 않는다.

`V1__baseline.sql`은 이미 develop에 빈 파일로 존재한다. 업무 테이블은 `V2`부터다.

```
V2__create_merchant_tables.sql   테이블 5개, 인덱스, 제약
V3__seed_products.sql            시드 상품
```

이미 적용된 마이그레이션 파일은 수정하지 않는다. 다음 버전 파일을 추가한다.

---

## 6. 핵심 알고리즘

이 절이 이 모듈에서 가장 틀리기 쉬운 부분이다. 구현 시간의 절반을 여기에 쓴다.

### 6.1 JCS 정규화

RFC 8785. 같은 의미의 JSON을 항상 같은 바이트로 만드는 규칙이다. 키를 정렬하고 공백을 제거하고 숫자와 문자열 표현을 고정한다.

서명하는 쪽과 검증하는 쪽이 다른 라이브러리를 쓰면 미묘하게 다른 바이트가 나올 수 있다. Core와 같은 라이브러리, 같은 버전을 쓰기로 하고 테스트 벡터를 교환한다(11절).

이 모듈에서 정규화 결과를 다시 계산하는 곳은 없다. 한 번 만들어 `canonical_checkout`에 저장하고 이후에는 저장된 문자열을 읽는다.

### 6.2 서명 대상 범위

Merchant Authorization의 서명 대상은 최상위 `com.*` 키를 제거한 Checkout이다(11.1). 중첩된 객체 안의 키는 건드리지 않는다.

이유는 순환이다. 서명 결과가 `com.<우리도메인>.payment.ap2_like.merchant_authorization`에 들어가므로 그 키를 포함해서 서명하면 자기 자신을 포함하는 값을 서명하게 된다.

```
1. Checkout 응답 객체를 만든다 (서명 확장 제외)
2. 최상위 com.* 키를 제거한다
3. JCS 정규화 → canonical_checkout에 저장
4. 서명 → merchant_authorization_jws에 저장
5. 응답을 조립할 때 com.*.ap2_like.merchant_authorization에 4의 결과를 넣는다
```

### 6.3 checkoutDigest

`base64url(SHA-256(...))`, 패딩 없음. 접두어 `sha256:`을 붙인다.

대상 범위가 미확정이다. 이 모듈은 `com.*` 제외 Checkout을 기준으로 하지만 설계서 11.1은 전체 Checkout을 기준으로 한다. 전체를 대상으로 하면 서명 확장이 포함되어 저장한 `canonical_checkout`으로는 대조가 안 된다. Core와 하나로 통일해야 한다(11절).

접두어는 11.1 공식에는 없지만 11.2·11.4·11.5의 Mandate·Credential·Receipt 예시가 전부 `sha256:...`이라 붙이는 쪽이 설계 의도로 판단했다.

### 6.4 ES256 Detached JWS

Detached는 페이로드를 JWS 문자열에서 빼는 방식이다. 페이로드가 이미 Checkout 응답 본문에 있으므로 서명 안에 또 넣으면 같은 데이터가 두 번 실려 간다.

서명 바이트는 R‖S 64바이트 raw여야 한다. Java 표준 JCA는 DER 인코딩을 뱉는다. 그대로 쓰면 다른 언어 구현이 검증하지 못한다. Nimbus의 `ECDSASigner`를 쓰거나 `ECDSA.transcodeSignatureToConcat`으로 변환한다.

ECDSA는 결정적이지 않다. 같은 입력을 두 번 서명하면 다른 값이 나온다. 도장을 두 번 찍으면 자국이 미묘하게 다른 것과 같다. 그래서 발급한 값을 그대로 보관한다(불변 조건 6번).

개인키는 설정 파일에 저장하지 않는다(18.5). 환경변수나 외부 파일 경로로 주입한다. `.gitignore`가 `*.pem`과 `*.key`를 막고 있다.

`kid`는 필수다. 활성 키와 검증 전용 이전 키를 Profile의 `keys[]`에 동시에 공개한다.

### 6.5 Checkout Mandate 검증 8항목

Complete 요청을 받았을 때 순서대로 확인한다(단계 13). 하나라도 실패하면 Pay를 호출하지 않는다.

```
1. AP2-like Capability가 이 세션에서 협상됐는가
2. Checkout Mandate가 존재하는가
3. 서명자가 신뢰하는 Platform 키인가
4. Mandate가 만료되지 않았는가
5. Mandate가 내 Checkout ID를 가리키는가
6. checkoutDigest가 현재 Checkout과 같은가
7. 금액·통화·line item이 현재 Checkout과 같은가
8. 내 Merchant Authorization이 Mandate 안에 문자열 그대로 포함돼 있는가
```

8번이 이 검증의 핵심이다. Platform이 서명한 Mandate 안에 내가 발급한 서명이 들어 있다는 것은, 사용자가 승인한 대상이 정확히 내가 확정한 그 체크아웃이라는 뜻이다.

7번의 line item 비교는 6번의 digest 비교로 대신할 수 있다. digest가 같으면 내용이 같다.

Platform 공개키는 `iss`가 가리키는 Platform Profile의 `keys[]`에서 `kid`로 찾는다.

### 6.6 RFC 9421 HTTP Message Signature

웹훅 발송에 쓴다. 헤더 다섯 개를 만든다.

```
UCP-Agent        내 Profile URI
Signature-Input  서명 대상 컴포넌트 목록과 created, keyid, alg
Signature        서명 값
Content-Digest   본문 해시
Webhook-Id       Core의 중복 판별 키
```

`created`가 서명에 포함되므로 발송할 때마다 새로 만든다(불변 조건 7번). Core가 `created` 허용 오차를 얼마로 두는지 확인이 필요하다(11절).

---

## 7. 상태 전이

### 7.1 Checkout

UCP 공식 상태다(16.1). Merchant가 결정하고 Core는 추측으로 바꾸지 않는다.

```
incomplete ──정보 보완──▶ incomplete
    │                          │
    │                          ├──모든 조건 충족──▶ ready_for_complete
    │                          │                          │
    └──API로 해결 불가──▶ requires_escalation              │ Complete 호출
                              │                            ▼
                              └──사용자 입력 후──▶  complete_in_progress
                                                            │
                                          ┌─────────────────┼─────────────────┐
                                          ▼                 ▼                 ▼
                                     completed      ready_for_complete    (UNKNOWN 유지)
                                                   (복구 가능한 실패)

  incomplete / requires_escalation / ready_for_complete ──▶ canceled
```

DB에는 대문자로 저장하고 API 응답에서는 소문자 snake로 변환한다. `INCOMPLETE` → `incomplete`, `READY_FOR_COMPLETE` → `ready_for_complete`.

전이는 전부 조건부 UPDATE로 한다.

```sql
UPDATE checkout_sessions
   SET status = 'COMPLETE_IN_PROGRESS', version = version + 1, updated_at = now()
 WHERE id = ? AND status = 'READY_FOR_COMPLETE' AND expires_at > now()
```

영향받은 행이 1이 아니면 이미 다른 요청이 처리 중이거나 만료된 것이다. 현재 상태를 읽어 적절한 오류를 반환한다.

`incomplete` 판정 기준은 배송이 필요한 상품인데 `fulfillment_address`가 없는 경우, 옵션이 선택되지 않은 경우, 시나리오가 재고 부족이나 배송 불가를 지시한 경우다. 사유는 `messages_json`에 `[{code, severity, message, target}]`로 담는다.

`expires_at`은 서명 시각 + 5분이다(D-03).

### 7.2 Payment

```
REQUESTED ──▶ SUCCESS
          ├──▶ FAILED
          └──▶ UNKNOWN ──재조회──▶ SUCCESS 또는 FAILED
```

갱신은 반드시 조건부 UPDATE로 한다.

```sql
UPDATE checkout_sessions
   SET payment_status = 'SUCCESS', pay_payment_id = ?, processor_transaction_id = ?
 WHERE id = ? AND payment_status = 'REQUESTED'
```

`UNKNOWN`에서 절대 하지 않는 것은 새 Payment 생성, 다른 멱등 키로 재청구, 주문 생성이다. 허용되는 것은 같은 키로 조회하는 것뿐이다.

### 7.3 Order

```
CREATED ──▶ CONFIRMED ──▶ SHIPPED ──▶ DELIVERED
    └──────────┴───────────┴──────▶ CANCELED
```

상태가 바뀔 때마다 `order.updated` 웹훅을 큐에 넣는다. 생성 시점에는 `order.created`.

### 7.4 Webhook

```
PENDING ──▶ DELIVERED
    └──실패──▶ PENDING (next_attempt_at 지수 백오프)
    └──횟수 초과──▶ FAILED
```

---

## 8. 멱등성과 장애 복구

### 8.1 Core에서 오는 멱등 키

여섯 종류다(17.4).

```
Cart Create        cart-create-{commerceSessionId}
Cart Update        cart-update-{externalCartId}-{platformRevision}
Checkout Create    checkout-create-{purchaseDraftId}
Checkout Update    checkout-update-{checkoutId}-{evidenceRevision}
Checkout Complete  checkout-complete-{purchaseRequestId}
Checkout Cancel    checkout-cancel-{checkoutId}
```

Complete만 응답을 저장해 재생한다. 나머지는 현재 상태를 조회해 반환하는 것으로 충분하다. Complete가 특별한 이유는 그 요청이 돈을 움직이기 때문이다.

같은 바디 판정은 요청 바디를 JCS 정규화한 뒤 SHA-256 한 값을 `completion_request_hash`와 비교한다. 공백이나 키 순서 차이로 충돌이 나지 않는다.

### 8.2 Pay로 나가는 멱등 키

`merchant-checkout-{checkoutId}` 하나다. 체크아웃 하나에 결제 시도가 몇 번이든 바뀌지 않는다. 타임아웃 뒤 재시도도 같은 키다.

기대하는 Pay 동작(17.5).

```
같은 Merchant + 같은 Key + 같은 Body  →  기존 Payment 결과 반환, PG 재호출 없음
같은 Merchant + 같은 Key + 다른 Body  →  409 IDEMPOTENCY_KEY_CONFLICT
다른 Key + 이미 소비된 Credential      →  409 CREDENTIAL_ALREADY_CONSUMED
```

### 8.3 결제 결과 불명

Pay가 5xx를 주거나 응답이 오지 않으면 `payment_status`를 `UNKNOWN`으로 남기고 주문을 만들지 않는다.

문제는 POST 응답이 유실된 경우 `paymentId`를 모른다는 것이다. 재조회 방식이 미확정이다(11절). 설계서 17.5대로면 같은 키와 같은 바디로 다시 POST하는 것이 답이고, 이 재호출이 PG를 절대 다시 부르지 않는다는 보장이 필요하다.

워커가 `(payment_status, payment_requested_at)` 인덱스로 스캔해 지수 백오프로 재조회한다. 횟수 제한을 둔다. 한도를 넘으면 사람이 확인하도록 남긴다.

### 8.4 워커 세 개

```
만료 스윕        (status, expires_at)로 스캔. 만료된 체크아웃을 canceled로
UNKNOWN 재조회   (payment_status, payment_requested_at)로 스캔. 지수 백오프, 횟수 제한
웹훅 재전송      (status, next_attempt_at)로 스캔. 서명은 매번 새로 생성
```

세 워커 모두 조건부 UPDATE로 작업을 집어 든다. 인스턴스가 하나뿐이라 분산 락은 두지 않는다.

---

## 9. 시나리오 제어

열 종류다(12.5).

```
NORMAL                          정상
PRICE_CHANGED                   Checkout Update 시 금액 변경
OUT_OF_STOCK                    재고 0 주입
ADDRESS_NOT_SERVICEABLE         배송 불가 지역
REQUIRES_ESCALATION             API로 해결 불가 상태
INVALID_MERCHANT_SIGNATURE      의도적으로 잘못된 서명 발급
COMPLETE_TIMEOUT_BEFORE_PROCESS Complete 수신 후 결제 전 응답 지연
COMPLETE_TIMEOUT_AFTER_ORDER    주문까지 만든 뒤 응답 유실
WEBHOOK_DUPLICATE               같은 webhook_id로 두 번 발송
WEBHOOK_DELAYED                 웹훅 발송 지연
```

상태는 DB가 아니라 인메모리 Map에 둔다. 시연과 테스트 중에만 쓰는 값이고 재기동 시 `NORMAL`로 돌아가는 것이 오히려 안전하다.

`COMPLETE_TIMEOUT_AFTER_ORDER`가 가장 중요하다. 주문이 실제로 만들어졌는데 Core가 그 사실을 모르는 상황을 만든다. Core가 재시도 대신 조회로 복구하는지 검증한다.

시나리오 제어 API는 `/internal`에 두어 Nginx가 외부에서 차단하도록 한다.

---

## 10. 오류 모델

공통 Envelope을 쓴다(20).

```json
{
  "errorCode": "...",
  "message": "...",
  "currentState": "...",
  "nextAction": "...",
  "retryable": false,
  "traceId": "..."
}
```

이 모듈이 내는 코드는 여덟 개다.

| errorCode | 상황 | retryable |
| --- | --- | --- |
| `CHECKOUT_INCOMPLETE` | 체크아웃 정보 부족 | false |
| `ADDRESS_REQUIRED` | 배송 필요 상품인데 배송지 없음 | false |
| `MERCHANT_HANDOFF_REQUIRED` | API로 처리 불가 | false |
| `CHECKOUT_PRICE_CHANGED` | 가격 변경 | false |
| `CHECKOUT_STOCK_INSUFFICIENT` | 재고 부족 | false |
| `MANDATE_SCOPE_MISMATCH` | Mandate와 Checkout 불일치 | false |
| `IDEMPOTENCY_KEY_CONFLICT` | 같은 키에 다른 바디 | false |
| `PAYMENT_UNKNOWN` | 결제 결과 불명 | false |

전부 `retryable`이 false다. 이 모듈에서 나는 오류 중 단순 재시도로 풀리는 것이 없다. 재시도로 풀릴 상황은 애초에 멱등 처리로 흡수한다.

민감정보를 응답과 로그에 넣지 않는다. Credential 원문, Mandate 원문 전체, 상세 배송지가 대상이다. 허용되는 식별자는 `traceId`, `merchantId`, `checkoutId`, `paymentId`, `orderId`, `webhookId`, digest 일부다.

---

## 11. 미확정과 합의 대기

여기 있는 항목은 아직 구현 근거가 없다. 확정되면 이 절에서 지우고 해당 절로 옮긴다.

**Core(허정원)와 합의 — Jira 168**

- `checkoutDigest` 대상 범위. `com.*` 제외 Checkout인지 전체 Checkout인지. 전체면 저장한 `canonical_checkout`으로 대조가 안 되어 6.3의 설계가 바뀐다. 가장 시급하다
- JCS 라이브러리와 정확한 버전. 테스트 벡터 교환
- Core→Merchant 인증 방식. D-10 권장은 API Key + `UCP-Agent` 바인딩. 헤더 이름과 키 전달 방식
- 멱등 키 6종을 실제로 보내는지. 특히 `cart-create-{commerceSessionId}`. 안 보내면 `carts.platform_ref` 삭제
- Platform Profile URL. Mandate 검증용 공개키를 가져오는 곳
- 웹훅 서명의 `created` 허용 오차
- `libs/ucp-contract`의 UCP DTO를 누가 작성하는지

**Pay(김지윤)와 합의**

- POST 응답 유실 시 재조회 방식. 같은 키 재POST인지 `GET /merchant/v1/payments?idempotencyKey=...`인지. 8.3의 워커 설계가 달라진다
- 토큰 엔드포인트 경로와 토큰 수명
- Credential 사전 검사 허용 여부와 `credential.type` 이름
- Checkout Mandate 형식(compact JWS)과 Platform 공개키 위치
- `FAILED`의 `failureCode` 목록
- request_hash 계산 방식

**셋이 함께**

- AP2-like Capability와 Payment Handler 네임스페이스(D-06, D-07). 설계서의 `com.example.payment.ap2_like`, `com.example.pay`, `COM_EXAMPLE_PAY_TOKEN`, `example_pay_merchant_a`는 전부 예시다. 팀이 통제하는 도메인의 역도메인으로 바꿔야 한다(27.1). 이게 정해져야 Profile 광고와 6.2의 키 제거 대상이 확정된다

---

## 12. 검증 기준

설계서 23.5가 요구하는 필수 테스트 열 개다. 이것이 통과하지 않으면 이 모듈은 완료가 아니다.

```
1  Profile Discovery
2  Payment Handler 광고
3  Custom AP2-like Capability 광고
4  Checkout 조건 서명
5  잘못된 Mandate 거절
6  현재 Checkout과 Mandate 조건 비교
7  Pay 성공 전 Order 생성 금지
8  Pay 성공 후 Order 한 번 생성
9  Complete 중복 호출에 기존 Order 반환
10 Order Webhook 서명·중복 전송
```

7·8·9번이 불변 조건 1~4를 직접 검증한다.

서명과 정규화는 통합 테스트를 기다리지 않는다. JCS와 ES256만 떼어내 단위 테스트를 먼저 만든다(Jira 173, 174). 알려진 입력과 출력 쌍을 고정해 두면 나중에 라이브러리나 버전을 바꿔도 회귀를 잡을 수 있다.

Pay 클라이언트는 stub으로 먼저 만든다. Pay API가 열리면 붙인다.

---

## 13. 성능 제약

목 서버다. 처리량 목표를 두지 않는다. 대신 두 가지를 지킨다.

Complete 검증 경로는 SELECT 한 번으로 끝난다. 조인 0개. 이건 성능보다 정합성 때문이다. 여러 번 읽으면 읽는 사이에 상태가 바뀔 수 있다.

카탈로그는 기동 시 전량 메모리에 올린다. 상품 수십 개 규모에서 매 요청 DB 조회보다 단순하다. 시나리오 API로 재고를 바꾸면 메모리 쪽도 같이 갱신한다.

---

## 14. 변경 기록

- 2026-09-07 최초 작성. 설계서 v2.1 기준. 데이터 접근을 JdbcTemplate으로 결정. 테이블 17개를 5개로 축소한 근거와 트레이드오프 명시.
