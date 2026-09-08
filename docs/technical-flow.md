# Technical Flow

## 버전 기록

| 버전 | 변경 내용 |
|---|---|
| v1 | 전체 시스템 동작을 세부 단계 기준으로 정의한 최초 Tech Flow. Core, MCP, Merchant, Pay 간 상호작용과 사용자 개입 지점을 포함하여 총 28개 단계로 상세 기술. |
| v2 | v1의 세부 흐름을 기능·책임 단위로 재구성하여 7개 핵심 단계로 축약. 중복된 내부 처리 단계를 통합하고, 시스템 간 책임 경계와 전체 거래 흐름이 명확하게 드러나도록 정리. |
| v3 | UCP 및 프로젝트 구현 기준으로 불필요한 Merchant Cart 단계를 제거하고 Core Cart에서 Merchant별 `line_items`를 직접 Checkout 생성에 사용하도록 수정. Complete Checkout의 중복 `amount`/`currency` 전달을 제거하고, Checkout 변경 시 재생성 대신 Update Checkout을 사용하도록 변경. **현재 구현 및 API/DTO 설계의 기준이 되는 최신 Tech Flow.** |

> **프로젝트 설계 기준:** 프로젝트 설계서 v2.1
> **Technical Flow 문서 버전:** v3
> API, DTO, DB 및 각 서비스 간 연동 설계는 별도 명시가 없는 한 v3 Tech Flow를 기준으로 한다.


## 1. 목적 및 책임 경계

본 문서는 서비스 간 기술 흐름과 책임 경계를 정의한다.

### Core
Core는 Orchestrator + Validation/Policy Layer 역할을 수행한다.

- 사용자 통합 Cart 관리
- Merchant Checkout 흐름 오케스트레이션
- Checkout 정책/의도 검증
- 승인 대상 Checkout 고정
- Pay 승인 요청 생성
- Merchant에 승인된 결제 권한 참조값 전달
- 주문/결제 통합 상태 제공

Core는 실제 결제를 실행하지 않고, PIN/생체인증도 처리하지 않는다.

### Merchant
Merchant는 Commerce 상태의 원본이다.

- Product / Price / Stock
- Checkout
- Order

Merchant는 자신의 최신 가격·재고·배송 조건으로 Checkout을 확정하고, 결제 성공 후 Order를 생성한다.

### Pay
Pay는 사용자 승인과 Payment의 원본이다.

- 결제수단 관리
- PIN / 생체인증
- Payment Approval 관리
- Payment Credential 발급
- Credential 검증
- 실제 Payment 실행

---

## 2. Cart / Checkout 흐름

사용자가 보는 장바구니는 Core의 통합 Cart이다.

하나의 Core Cart에는 여러 Merchant의 상품이 포함될 수 있다.

```text
Core Cart
 ├─ Merchant A 상품
 ├─ Merchant A 상품
 └─ Merchant B 상품
```

Core는 별도의 Merchant Cart를 생성하거나 동기화하지 않는다.

사용자가 주문 준비를 시작하면 Core가 Core Cart의 상품을 Merchant별로 분리하고, 해당 Merchant의 `line_items`와 배송 정보를 이용해 Merchant Checkout을 직접 생성한다.

```text
Core Cart
   ↓
Merchant별 상품 분리
   ↓
line_items + 배송 정보
   ↓
Merchant Checkout 생성
```

Merchant는 자신의 최신 데이터를 기준으로 다음을 확정한다.

- 가격
- 재고
- 옵션
- 배송 가능 여부
- 배송비
- 총액

Checkout의 최종 원본은 Merchant이다.

---

## 3. 검증 및 승인 준비

Core는 Merchant가 반환한 Checkout을 그대로 승인 단계로 넘기지 않는다.

Core는 Merchant의 가격·재고·배송비 자체를 다시 원본처럼 재계산하지 않고, 다음 내용을 중심으로 정책/의도 검증을 수행한다.

- 요청한 상품 / 옵션 / 수량과 Checkout 응답의 대응 여부
- Checkout 상태의 유효성
- 사용자 예산 및 구매 조건 충족 여부
- 가격·배송 조건 변경이 사용자 허용 범위 내인지 여부
- 기타 정책 위반 여부

Merchant가 확정한 가격·재고·배송비·총액의 최종 원본은 Merchant Checkout이다.

검증에 실패하면 사용자 승인을 생성하지 않는다.

검증에 성공하면 Core는 사용자가 승인할 Checkout 상태를 고정한다.

승인 대상 고정은 별도 Order Snapshot을 생성하는 방식 대신, Checkout 식별자와 승인 시점의 Checkout을 식별할 수 있는 `checkoutHash` 또는 AP2-like Mandate 방식으로 처리한다.

```text
checkoutId
checkoutHash
```

이후 Core는 Pay에 다음과 같은 최소 정보를 이용해 Payment Approval을 생성한다.

```text
checkoutId
checkoutHash
merchantName
amount
currency
```

---

## 4. 사용자 승인

사용자는 Pay 앱에서 승인 대기 건을 확인한다.

Pay 앱에서:

1. 주문 내용을 확인한다.
2. 결제수단을 선택한다.
3. PIN 또는 생체인증을 수행한다.
4. 승인 또는 거부한다.

PIN 및 생체인증은 Pay 영역에서 처리한다.

Core는 PIN 원문이나 생체정보를 직접 처리하지 않는다.

승인이 성공하면 Pay는 해당 승인 범위에 제한된 일회성 Payment Credential을 발급한다.

Core에는 Credential 원문이 아니라 다음 참조정보만 전달한다.

```text
approvalId
status = APPROVED
credentialId
expiresAt
```

---

## 5. 결제 및 Order 생성

승인이 완료되면 Core는 Merchant에 Complete Checkout을 요청한다.

요청에는 다음 정보가 포함된다.

```text
checkoutId
credentialId
Idempotency-Key
```

Merchant는 자신의 authoritative Checkout 상태를 기준으로 결제 컨텍스트를 구성한다.

이후 Merchant가 Pay에 실제 Payment를 요청한다.

```text
merchantId
checkoutId
credentialId
amount
currency
Idempotency-Key
```

`amount`와 `currency`는 Core가 다시 전달하지 않고 Merchant가 자신의 Checkout에서 가져온 값을 사용한다.

Pay는 Credential scope가 다음 조건에 맞는지 검증한다.

- 올바른 Merchant인지
- 올바른 Checkout인지
- 금액과 통화가 일치하는지
- 만료되지 않았는지
- 이미 사용된 Credential이 아닌지

검증에 성공하면 Pay가 실제 Payment를 실행한다.

정상 흐름은 다음과 같다.

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
Core
```

Merchant는 Payment가 `SUCCESS`인 경우에만 정식 Order를 생성한다.

Merchant는 Complete Checkout의 동기 응답으로 Core에 최소한 다음 결과를 반환한다.

```text
orderId
paymentId
paymentStatus
orderStatus
```

Core는 이를 기존 Approval / Checkout과 연결해 사용자에게 주문 및 결제 내역을 제공한다.

---

## 6. 예외 흐름

### Checkout 변경

사용자 승인 이후 Checkout의 다음 내용이 변경되면 기존 승인을 재사용하지 않는다.

- 상품
- 옵션
- 수량
- 가격
- 배송비
- 총액

기존 Approval과 Credential을 무효화하고 다음 흐름을 다시 수행한다.

```text
Update Checkout
 ↓
Core 정책/의도 검증
 ↓
새 승인 대상 고정
(checkoutHash / mandate)
 ↓
새 Approval
 ↓
사용자 재승인
 ↓
새 Credential
```

### Payment Timeout / UNKNOWN

Payment 결과가 Timeout 또는 `UNKNOWN`이면 자동으로 새 결제를 실행하지 않는다.

기존 Payment의 실제 상태를 먼저 조회하거나 reconcile한 뒤 최종 상태를 반영한다.

---

## 7. 전체 Sequence

```text
User
 ↓
Agent
 ↓
MCP Server
 ↓
Core
 │
 │ Core Cart를 Merchant별로 분리
 │ line_items + 배송 정보
 ▼
Merchant
 │ Checkout 생성
 │ 가격·재고·배송비 확정
 ▼
Core
 │ Checkout 정책/의도 검증
 │ 승인 대상 Checkout 고정
 │ (checkoutHash / mandate)
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
 │ checkoutId
 │ credentialId
 ▼
Merchant
 │ 자신의 Checkout 기준 결제조건 조회
 │ Payment 요청
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
 │ 주문/결제 상태 연결
 ▼
Agent / App
```