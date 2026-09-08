# Technical Flow

## 버전 기록

| 버전 | 변경 내용 |
|---|---|
| v1 | 전체 시스템 동작을 세부 단계 기준으로 정의한 최초 Tech Flow. Core, MCP, Merchant, Pay 간 상호작용과 사용자 개입 지점을 포함하여 총 28개 단계로 상세 기술. |
| v2 | v1의 세부 흐름을 기능·책임 단위로 재구성하여 7개 핵심 단계로 축약. 중복된 내부 처리 단계를 통합하고 시스템 간 책임 경계를 명확히 함. |
| v3 | Merchant Cart 단계를 제거하고 Core Cart에서 Merchant별 `line_items`를 직접 Checkout 생성에 사용하도록 수정. Complete Checkout의 중복 `amount`/`currency` 전달을 제거하고 Checkout 변경 시 재생성 대신 Update Checkout을 사용하도록 변경. |
| v4 | UCP Profile Discovery 및 Capability Negotiation을 명시하고 Core를 Platform, Merchant를 Business로 정의. UCP Cart Capability는 사용하지 않고 Catalog / Checkout / Order Capability를 사용하도록 확정. Core Platform Profile과 Order Webhook을 추가하여 주문 생성 이후 Merchant의 Order lifecycle 변경을 Core에 전달하도록 확장. |

> **프로젝트 설계 기준:** 프로젝트 설계서 v2.1
> **Technical Flow 문서 버전:** v4
> API, DTO, DB 및 서비스 간 연동 설계는 별도 명시가 없는 한 v4 Technical Flow를 기준으로 한다.

---

# 1. 목적 및 책임 경계

본 문서는 Core, Merchant, Pay 간 기술 흐름과 책임 경계를 정의한다.

UCP 관점에서 역할은 다음과 같다.

```text
Core     = Platform
Merchant = Business
```

## Core

Core는 Orchestrator + Validation / Policy Layer 역할을 수행한다.

- UCP Platform Profile 제공
- Merchant Profile Discovery
- Capability Negotiation
- 상품 탐색 오케스트레이션
- 사용자 통합 Cart 관리
- Merchant Checkout 흐름 오케스트레이션
- Checkout 정책/의도 검증
- 승인 대상 Checkout 고정
- Pay 승인 요청 생성
- Merchant에 승인된 결제 Credential 전달
- Merchant Order lifecycle 이벤트 수신
- 주문/결제 통합 상태 제공

Core는 실제 결제를 실행하지 않는다.

Core는 PIN 또는 생체인증을 처리하지 않는다.

## Merchant

Merchant는 Commerce 상태의 원본이다.

- Catalog / Product
- Price / Stock
- Checkout
- Order
- Fulfillment

Merchant는 자신의 최신 Commerce 데이터를 기준으로 Checkout을 확정한다.

Payment 성공 후 Order를 생성하며, 이후 주문·배송 상태의 authoritative source 역할을 한다.

## Pay

Pay는 사용자 승인과 Payment의 원본이다.

- 결제수단 관리
- PIN / 생체인증
- Payment Approval 관리
- Payment Credential 발급
- Credential 검증
- 실제 Payment 실행
- Payment 상태 관리

---

# 2. UCP Profile Discovery / Capability Negotiation

Core와 Merchant는 거래 시작 전 서로 지원하는 UCP 기능을 확인한다.

## 2.1 Merchant Profile Discovery

Core는 Merchant의 UCP Business Profile을 조회한다.

```text
Core
 ↓
GET Merchant /.well-known/ucp
 ↓
Merchant Business Profile
```

Merchant Profile에는 최소한 다음 정보가 포함된다.

```text
UCP version
REST endpoint
Capabilities
Payment Handlers
Signing Keys
```

Core는 Profile에서 Merchant가 제공하는 UCP REST base endpoint를 확인한다.

예:

```text
http://ucp-merchant-mock:8080/ucp/v1
```

## 2.2 Core Platform Profile

Core도 Merchant가 조회할 수 있는 UCP Platform Profile을 제공한다.

```text
GET Core /.well-known/ucp
```

Core는 Merchant에 요청할 때 자신의 Platform Profile URI를 `UCP-Agent`로 전달한다.

```text
UCP-Agent:
profile="<Core /.well-known/ucp>"
```

Merchant는 해당 Profile을 조회하여 Core가 지원하는 Capability와 설정을 확인한다.

## 2.3 Capability Negotiation

Merchant와 Core가 공통으로 지원하는 Capability만 활성화한다.

프로젝트의 활성 Capability는 다음과 같다.

```text
Catalog Search
Catalog Lookup
Checkout
Order
Fulfillment
```

프로젝트에서는 UCP Cart Capability를 사용하지 않는다.

따라서 다음 구조는 사용하지 않는다.

```text
Core Cart
 ↓
Merchant Cart
 ↓ cart_id
Merchant Checkout
```

대신 Core의 자체 Cart를 유지하고 Checkout 생성 시 Merchant별 `line_items`를 직접 전달한다.

```text
Core Cart
 ↓
Merchant별 line_items
 ↓
Merchant Checkout
```

---

# 3. 상품 탐색 / Core Cart

상품 탐색은 Merchant의 Catalog Capability를 이용한다.

```text
Agent
 ↓
MCP Server
 ↓
Core
 ↓
Merchant Catalog
```

주요 Merchant 연동은 다음과 같다.

```text
Catalog Search
Catalog Lookup
Product Detail
```

Catalog에서 반환되는 가격·재고 정보는 탐색과 비교를 위한 현재 정보이다.

최종 거래 조건의 authoritative source는 Checkout이다.

사용자가 상품을 선택하면 Core가 자신의 통합 Cart에 저장한다.

```text
Core Cart
 ├─ Merchant A 상품
 ├─ Merchant A 상품
 └─ Merchant B 상품
```

Merchant Cart는 생성하지 않는다.

---

# 4. Checkout 생성 및 갱신

사용자가 구매 진행을 시작하면 Core는 Core Cart를 Merchant별로 분리한다.

```text
Core Cart
 ↓
Merchant별 상품 분리
 ↓
line_items
 ↓
Merchant Checkout 생성
```

실물 배송 상품인 경우 Fulfillment 정보를 함께 구성한다.

Merchant는 자신의 최신 데이터로 다음 항목을 확정한다.

- 상품
- 옵션
- 수량
- 가격
- 재고
- 배송 가능 여부
- 배송 방법
- 배송비
- 총액
- 통화

Checkout의 Commerce 정보는 Merchant가 authoritative source이다.

## Checkout 변경

배송지, 수량, 옵션 등 Checkout 내용이 변경되는 경우 기존 Checkout을 새로 만들지 않고 Update Checkout을 사용한다.

```text
Core
 ↓
PUT Merchant Checkout
 ↓
Merchant
 ↓
최신 Checkout 반환
```

Update Checkout은 현재 Checkout 상태를 기준으로 갱신하며 Merchant가 새로운 가격·재고·배송 조건을 다시 계산한다.

---

# 5. Checkout 검증 및 승인 준비

Core는 Merchant Checkout을 그대로 승인 단계로 전달하지 않는다.

다음 항목을 중심으로 정책 및 사용자 의도를 검증한다.

- 요청한 상품과 Checkout 상품의 대응 여부
- 옵션 및 수량 일치 여부
- Checkout 상태 유효성
- 사용자 예산 조건
- 가격 변경 허용 범위
- 배송비 및 배송조건 변경 허용 범위
- 기타 사용자 정책 위반 여부

Core는 Merchant의 가격·재고·배송비를 원본처럼 다시 계산하지 않는다.

검증 실패 시 Payment Approval을 생성하지 않는다.

검증 성공 시 사용자가 승인할 Checkout 상태를 고정한다.

```text
checkoutId
checkoutHash
```

또는 구현 범위에 따라 AP2-like Mandate를 사용할 수 있다.

Core는 Pay에 승인 요청을 생성한다.

```text
checkoutId
checkoutHash
merchantName
amount
currency
```

여기서 `amount`와 `currency`는 Merchant Checkout에서 확정된 값을 이용한다.

---

# 6. 사용자 승인

사용자는 Pay 앱에서 승인 대기 건을 확인한다.

Pay 앱에서 다음 과정을 수행한다.

1. 주문 내용 확인
2. 결제수단 선택
3. PIN 또는 생체인증
4. 승인 또는 거부

PIN 및 생체인증은 Pay 영역에서 처리한다.

Core와 Merchant는 PIN 원문 또는 생체정보를 처리하지 않는다.

승인 성공 시 Pay는 해당 Checkout 범위에 제한된 Payment Credential을 발급한다.

Core에는 다음 참조정보만 전달한다.

```text
approvalId
status = APPROVED
credentialId
expiresAt
```

---

# 7. Complete Checkout / Payment / Order 생성

승인이 완료되면 Core는 Merchant에 Complete Checkout을 요청한다.

UCP Payment Handler 구조에서는 Pay가 발급한 `credentialId`를 Payment Instrument의 Credential로 전달한다.

개념적으로 다음과 같다.

```text
checkoutId

payment
 └─ instrument
      └─ credential
           └─ credentialId

Idempotency-Key
```

Core는 `amount`와 `currency`를 Complete Checkout 요청에 중복 전달하지 않는다.

Merchant는 자신의 authoritative Checkout에서 결제 조건을 조회한다.

```text
merchantId
checkoutId
credentialId
amount
currency
Idempotency-Key
```

Merchant가 Pay에 Payment를 요청한다.

```text
Merchant
 ↓
Pay
 ↓
Credential Scope 검증
 ↓
PG / Mock PG
```

Pay는 다음 조건을 검증한다.

- Merchant 일치 여부
- Checkout 일치 여부
- 금액 일치 여부
- 통화 일치 여부
- Credential 만료 여부
- Credential 사용 여부

정상 흐름:

```text
Core
 ↓ Complete Checkout
Merchant
 ↓ Payment Request
Pay
 ↓
PG / Mock PG
 ↓ SUCCESS
Pay
 ↓
Merchant
 ↓ Order 생성
 ↓ Complete Checkout Response
Core
```

Merchant는 Payment가 `SUCCESS`인 경우에만 Order를 생성한다.

Complete Checkout의 정상 응답으로 Core는 최소한 다음 관계를 확인할 수 있어야 한다.

```text
checkoutId
orderId
paymentId
paymentStatus
orderStatus
```

UCP wire format에서는 Checkout의 `status = completed`와 `order` 정보를 기본으로 사용하고, 프로젝트 내부 Payment 식별정보는 프로젝트 Payment Handler 또는 내부 매핑을 통해 연결한다.

Core는 Checkout / Approval / Payment / Order 관계를 저장하여 사용자에게 통합 상태를 제공한다.

---

# 8. Order Lifecycle / Webhook

Order 생성 이후 Commerce 상태의 원본은 Merchant이다.

예:

```text
ORDER CREATED
 ↓
PROCESSING
 ↓
SHIPPED
 ↓
IN_TRANSIT
 ↓
DELIVERED
```

Merchant는 Order 상태가 변경되면 Core가 Platform Profile에 제공한 Order Webhook으로 현재 Order 상태를 전달한다.

```text
Merchant
 ↓
POST Core /webhooks/ucp/orders
 ↓
Core
```

Core는 Webhook으로 전달된 Order snapshot을 기존 Checkout / Order와 연결하여 통합 주문 상태를 갱신한다.

Webhook 요청은 이벤트 식별자와 발생 시각을 포함한다.

```text
Webhook-Id
Webhook-Timestamp
```

동일 Webhook 이벤트가 재전송되더라도 Core는 중복 반영하지 않는다.

Webhook 전달이 실패하면 Merchant는 재시도할 수 있다.

## Webhook 유실 / 지연

Core가 상태를 확인할 필요가 있거나 Webhook 전달이 유실된 것으로 판단되면 Merchant의 Get Order를 이용한다.

```text
Core
 ↓
GET Merchant /orders/{orderId}
 ↓
Authoritative Order
```

Merchant가 반환한 최신 Order가 최종 원본이다.

---

# 9. 예외 흐름

## Checkout 변경

사용자 승인 이후 다음 항목이 변경되면 기존 승인을 재사용하지 않는다.

- 상품
- 옵션
- 수량
- 가격
- 배송비
- 총액

기존 Approval과 Credential을 무효화한다.

```text
Update Checkout
 ↓
Merchant 최신 Checkout
 ↓
Core 정책/의도 재검증
 ↓
새 승인 대상 고정
 ↓
새 Approval
 ↓
사용자 재승인
 ↓
새 Credential
```

## Complete Checkout 응답 유실

Complete Checkout 요청 후 응답을 받지 못했다고 즉시 새 결제를 실행하지 않는다.

Core는 기존 Checkout 상태를 조회한다.

```text
GET /checkout-sessions/{checkoutId}
```

Merchant Checkout이 이미 완료되었다면 기존 결과를 사용한다.

새 Complete 요청이 필요한 경우 동일 요청에 대해서는 기존 Idempotency-Key를 재사용한다.

## Payment Timeout / UNKNOWN

Payment 결과가 Timeout 또는 `UNKNOWN`이면 자동으로 새 Payment를 실행하지 않는다.

Merchant와 Pay가 기존 Payment 상태를 먼저 조회하거나 reconcile한다.

최종 결과가 확인된 후 Checkout / Order 상태를 확정한다.

---

# 10. 전체 Sequence

```text
[UCP Discovery]

Core
 ↓ GET /.well-known/ucp
Merchant
 ↓ Business Profile
Core

Core 요청
 └─ UCP-Agent: Core Platform Profile

Merchant
 ↓ GET Core Platform Profile
Core
 ↓
Capability Intersection

Active:
- Catalog Search
- Catalog Lookup
- Checkout
- Order
- Fulfillment

Not Active:
- Cart


[상품 탐색]

User
 ↓
Agent
 ↓
MCP Server
 ↓
Core
 ↓ Catalog Search / Lookup
Merchant


[Checkout]

Core Cart
 ↓
Merchant별 line_items 분리
 ↓
Merchant
 │ Checkout 생성
 │ 가격·재고·배송비·총액 확정
 ▼
Core
 │ Checkout 정책/의도 검증
 │ 승인 대상 고정
 │ checkoutId + checkoutHash
 ▼
Pay
 │ Payment Approval 생성
 ▼
Pay App
 │ 주문 확인
 │ 결제수단 선택
 │ PIN / 생체인증
 ▼
Pay
 │ Approval 승인
 │ Credential 발급
 ▼
Core
 │ credentialId
 ▼
Merchant
 │ Complete Checkout
 │ 자신의 Checkout 기준 결제조건 조회
 ▼
Pay
 │ Credential scope 검증
 │ Payment 실행
 ▼
PG / Mock PG
 │
 │ SUCCESS
 ▼
Pay
 ▼
Merchant
 │ Order 생성
 ▼
Core
 │ Checkout / Payment / Order 연결


[Post Order]

Merchant
 │ 주문 / 배송 상태 변경
 ▼
Core Order Webhook
 │
 │ 통합 주문상태 갱신
 ▼
Agent / App

Webhook 유실 또는 상태 확인 필요
Core
 ↓ GET /orders/{orderId}
Merchant
 ↓ 최신 Order
Core
```