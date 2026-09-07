# AI 이상거래 탐지 시나리오 정의서

## 1. 문서 목적

Payment MCP 환경에서 발생할 수 있는 정상 거래와 이상거래를 정의하고, 각 시나리오를 UCP / Policy·Rule / AI 관점으로 분류한다.
본 문서는 AI Fraud Detection MVP에서 실제 탐지할 시나리오를 선정하고, 이후 Feature 정의·모델 학습·Risk Score 설계의 기준으로 사용한다.

---

## 2. 적용 범위 및 전제

- 사용자는 LLM을 통해 상품 구매를 요청한다.
- Payment MCP는 결제 과정에서 사용자·거래·접속·배송 관련 정보를 수집한다.
- AI는 결제 과정에서 필요한 위험 Feature를 수집·갱신하고, **최종 결제 실행 직전에 전체 Feature를 기반으로 Final Risk Score를 산출**한다.
- 명확한 정책 위반(예: 승인 금액 초과, 중복 결제)은 AI 판정까지 기다리지 않고 Policy / Rule / Idempotency 계층에서 처리할 수 있다.
- LOW / MEDIUM / HIGH의 숫자 Threshold는 본 Story에서 고정하지 않으며, 모델 평가 및 정책 Story에서 확정한다.
- 데이터 기반 시나리오는 `dataset_description.md`에 정의된 UAE 전자상거래 Synthetic Fraud Dataset의 Feature를 기준으로 한다.
- MCP 특화 시나리오 중 현재 Dataset에 없는 Feature는 별도 서비스 DB 또는 Synthetic Data가 필요하다.

---

## 3. 정상 결제 프로세스 및 정상 사용자 행동

### 3.1 정상 결제 프로세스

```text
사용자 구매 요청
        ↓
LLM
        ↓
Payment MCP
        ↓
거래 / 사용자 / 접속 / 배송 정보 수집
        ↓
명확한 Policy / Rule 위반 검사
        ↓
AI 입력 Feature 구성
        ↓
최종 결제 직전 Fraud Detection AI 추론
        ↓
Final Risk Score
        ↓
Policy Engine
        ↓
LOW     → 정상 결제
MEDIUM  → 추가 인증
HIGH    → 결제 거부
```

### 3.2 F00 — 정상 거래

정상 거래는 사용자의 과거 거래 패턴과 비교했을 때 현재 거래의 금액·거래 속도·접속 환경·배송 정보에서 뚜렷한 위험 신호가 누적되지 않는 거래로 정의한다.

**주요 정상 신호**
- 현재 결제금액이 과거 평균 거래금액에서 크게 이탈하지 않음
- 단시간 거래 횟수가 급격히 증가하지 않음
- IP 위험도가 낮음
- 배송지와 청구지가 일치하거나 다른 위험 신호가 없음
- 신규 계정이더라도 고액·반복·고위험 IP 등의 이상 신호가 동시에 나타나지 않음

---

## 4. 이상거래 시나리오 목록

| ID | 구분 | 시나리오 | 기본 위험도 | 현재 Dataset | UCP | Rule/Policy | AI | AI MVP |
|---|---|---|---|---|---|---|---|---|
| F01 | 일반 Fraud | 비정상 고액 결제 | MEDIUM | 직접 가능 | 부분 | 부분 | 높음 | 예 |
| F02 | 일반 Fraud | 단시간 반복 결제 | HIGH | 직접 가능 | 부분 | 가능 | 높음 | 예 |
| F03 | 일반 Fraud | 비정상 시간대 결제 | LOW | 직접 가능 | 부분 | 부분 | 중간 | 아니오 |
| F04 | 일반 Fraud | 신규 계정 이상 결제 | MEDIUM | 직접 가능 | 낮음 | 가능 | 높음 | 예 |
| F05 | 일반 Fraud | 과거 차지백 이력 + 현재 이상행동 | MEDIUM | 직접 가능 | 낮음 | 부분 | 높음 | 아니오 |
| F06 | 일반 Fraud | 고위험 IP 결제 | HIGH | 직접 가능 | 부분 | 가능 | 높음 | 예 |
| F07 | 일반 Fraud | 카드 국가 정보 불일치 | MEDIUM | 직접 가능 | 부분 | 가능 | 중간 | 아니오 |
| F08 | 일반 Fraud | 배송지/청구지 불일치 | MEDIUM | 직접 가능 | 부분 | 가능 | 중간~높음 | 예 |
| F09 | 일반 Fraud | 다수 고가 상품 일괄 구매 | MEDIUM | 직접 가능 | 부분 | 가능 | 중간 | 아니오 |
| F10 | 일반 Fraud | 구매 카테고리 급변 | MEDIUM | Feature Engineering 필요 | 부분 | 부분 | 높음 | 아니오 |
| F11 | MCP 특화 | 사용자 승인 금액 초과 | HIGH | 현재 Dataset 미지원 | 부분 | 높음 | 낮음 | 아니오 |
| F12 | MCP 특화 | 승인 후 상품 변경 | HIGH | 현재 Dataset 미지원 | 부분 | 높음 | 낮음 | 아니오 |
| F13 | MCP 특화 | 승인 후 Merchant 변경 | HIGH | 현재 Dataset 미지원 | 부분 | 높음 | 낮음 | 아니오 |
| F14 | MCP 특화 | 중복 결제 | HIGH | 현재 Dataset 미지원 | 부분 | 높음 | 낮음 | 아니오 |
| F15 | MCP 특화 | Agent 과도한 구매 | HIGH | 현재 Dataset 미지원 | 부분 | 부분 | 높음 | 아니오 |

총 **15개 이상거래 시나리오**를 정의하였으며, 이 중 **F11~F15의 5개를 MCP/AI Agent 특화 시나리오**로 분류한다.

---

## 5. 시나리오 상세 정의

### F01 — 비정상 고액 결제 / MEDIUM

사용자의 현재 결제금액이 과거 평균·중앙값·표준편차에 비해 크게 증가한 거래이다.

- 주요 Feature: `amount_krw`, `user_avg_amount_prev`, `amount_ratio`, `amount_zscore`
- UCP/Rule: 사용자가 지정한 절대 구매한도 초과는 Policy로 방어할 수 있으나 개인별 소비패턴 이탈 판단은 제한적이다.
- AI 역할: 개인별 과거 거래와 현재 거래를 비교하여 비정상 고액 여부를 판단한다.
- MVP: **선정**

### F02 — 단시간 반복 결제 / HIGH

동일 사용자의 거래가 짧은 시간 동안 급격히 증가하는 상황이다.

- 주요 Feature: `txn_count_10m`, `txn_count_1h_calc`, `txn_count_24h_calc`, `high_velocity`
- UCP/Rule: 명백한 거래 폭주는 Rate Limit Rule로 제한할 수 있다.
- AI 역할: 사용자별 정상 거래 속도와 현재 Velocity를 함께 평가한다.
- MVP: **선정**

### F03 — 비정상 시간대 결제 / LOW

사용자의 평소 거래 시간대와 크게 다른 시점에 발생하는 결제이다.

- 주요 Feature: `kst_hour`, `is_night`, `user_avg_hour_prev`, `hour_deviation`
- 단독 차단 신호로 사용하지 않고 다른 이상 신호를 보조하는 Feature로 활용한다.

### F04 — 신규 계정 이상 결제 / MEDIUM

계정 생성 후 경과일이 짧은 사용자에게서 고액 등 비정상적인 거래 행동이 발생하는 경우이다.

- 주요 Feature: `user_account_age_days`, `is_fresh_account`, `amount_ratio`, `ip_risk_score`
- 신규 계정 자체는 Fraud가 아니므로 다른 위험 신호와 결합해 평가한다.
- MVP: **선정**

### F05 — 과거 차지백 이력 + 현재 이상행동 / MEDIUM

과거 차지백 이력이 있는 사용자가 현재 고액·반복 결제 등 추가적인 이상 행동을 보이는 거래이다.

- 주요 Feature: `user_prev_chargebacks`, `user_is_high_risk`, `amount_ratio`, `high_velocity`
- 차지백 이력만으로 거래를 차단하지 않고 현재 행동과 함께 평가한다.

### F06 — 고위험 IP 결제 / HIGH

결제 시점의 IP Risk Score가 높고 다른 거래 위험 신호가 함께 나타나는 경우이다.

- 주요 Feature: `ip_risk_score`, `amount_ratio`, `shipping_billing_match`
- UCP는 접속·거래 Context 전달에 활용하고, IP 위험도 산출은 Risk Service 또는 Mock Service가 담당한다.
- AI는 IP 위험도와 다른 이상 행동을 종합한다.
- MVP: **선정**

### F07 — 카드 국가 정보 불일치 / MEDIUM

카드 국가 정보와 거래 Context 사이에 불일치가 발생하는 거래이다.

- 주요 Feature: `card_country_match`, `ip_risk_score`, `shipping_billing_match`
- 해외 이용 등 정상 상황이 있을 수 있어 단독 차단보다 보조 Risk Feature로 사용한다.

### F08 — 배송지/청구지 불일치 / MEDIUM

상품 배송지와 결제 청구지의 관계가 일치하지 않는 거래이다.

- 주요 Feature: `shipping_billing_match`, `city_match`, `ip_risk_score`
- 선물·회사 배송 등 정상적인 불일치가 존재할 수 있으므로 다른 신호와 결합한다.
- MVP: **선정**

### F09 — 다수 고가 상품 일괄 구매 / MEDIUM

평소보다 많은 상품과 높은 총액이 동시에 발생하는 거래이다.

- 주요 Feature: `items_count`, `amount_krw`, `amount_per_item`
- 사용자별 평소 상품 수와 금액 대비 이탈 정도를 중심으로 판단한다.

### F10 — 구매 카테고리 급변 / MEDIUM

사용자의 과거 구매 카테고리 분포와 다른 상품군에서 거래가 발생하는 경우이다.

- 원본 `merchant_category`와 사용자 거래 이력을 이용하여 `new_category`, `category_frequency` 등의 Feature Engineering이 필요하다.

### F11 — 사용자 승인 금액 초과 / HIGH / MCP 특화

사용자가 승인한 금액보다 최종 결제금액이 증가한 거래이다.

- 필요 Feature: `approved_amount`, `final_amount`, `approval_amount_gap`
- 현재 Fraud Dataset에는 승인 Context가 없어 별도 DB가 필요하다.
- 명확한 정책 위반이므로 AI보다 Policy/Rule 계층에서 차단하는 것이 우선이다.

### F12 — 승인 후 상품 변경 / HIGH / MCP 특화

사용자 승인 이후 상품·수량·가격 등 장바구니 상태가 변경된 경우이다.

- 필요 Feature: `approved_cart`, `final_cart`, `cart_hash_match`
- AI보다 승인 상태와 최종 Checkout 상태의 무결성 검증이 우선이다.

### F13 — 승인 후 Merchant 변경 / HIGH / MCP 특화

사용자가 승인한 판매처와 실제 결제가 수행되는 판매처가 달라진 경우이다.

- 필요 Feature: `approved_merchant`, `final_merchant`, `merchant_match`
- 결정론적 Policy 검증 대상이다.

### F14 — 중복 결제 / HIGH / MCP 특화

네트워크 재시도 또는 Agent 재호출로 동일 주문이 반복 결제되는 상황이다.

- 필요 Feature: `order_id`, `idempotency_key`, `payment_attempt_id`
- AI보다 Idempotency 및 거래 상태 관리로 방어한다.

### F15 — Agent 과도한 구매 / HIGH / MCP 특화

사용자가 표현한 예산·상품 수·구매 범위보다 AI Agent가 실제 구매 범위를 과도하게 확대하는 상황이다.

- 필요 Feature: `requested_budget`, `final_amount`, `requested_item_count`, `final_item_count`, `intent_deviation`
- 현재 Dataset에는 사용자 Intent가 없어 별도 Context 저장 및 Synthetic Data가 필요하다.
- 명확한 예산 위반은 Policy가 담당하고, 애매한 의도 이탈은 AI 보조 판단 대상으로 확장할 수 있다.

---

## 6. 시나리오별 위험도 정의

### LOW
단독으로 Fraud를 강하게 의미하지 않으며 보조 Feature로 사용하는 위험 신호이다.

### MEDIUM
정상 거래에서도 발생할 수 있으나 추가적인 검증이 필요한 위험 신호이다. 다른 이상 신호와 결합될 경우 HIGH로 상승할 수 있다.

### HIGH
Fraud 연관성이 높거나 명확한 정책·거래 무결성 위반에 해당하는 위험 신호이다.

> 각 시나리오의 `기본 위험도`는 단독 발생 시의 설계 기준이며, 최종 결제 처리는 개별 시나리오 등급을 단순 합산하지 않고 AI의 Final Risk Score 및 Policy 결과를 기반으로 결정한다.

---

## 7. UCP / Rule / AI 역할 구분

### UCP
- 결제·Checkout 과정에 필요한 거래 Context 전달
- AI/FDS가 활용할 수 있는 입력 정보의 전달 계층
- Fraud 여부를 직접 분류하는 모델 역할은 하지 않음

### Policy / Rule
- 사용자가 명시한 금액 한도 초과
- Rate Limit
- 승인 상태와 최종 거래 상태 불일치
- 중복 결제
등 명확한 조건을 결정론적으로 검증한다.

### Fraud Detection AI
- 개인별 금액 이상
- 사용자별 거래 속도 이상
- 계정 위험
- IP 위험
- 주소 불일치
등 단일 Rule만으로 판단하기 어려운 위험 신호를 종합하여 **Final Risk Score**를 산출한다.

---

## 8. AI MVP 탐지 대상

현재 Dataset에서 실제 Feature 구성이 가능하고 시연 구조와 직접 연결할 수 있는 다음 **5개 시나리오**를 MVP 탐지 대상으로 선정한다.

| ID | 시나리오 | 기본 위험도 | 주요 Feature | 주 대응 |
|---|---|---|---|---|
| F01 | 비정상 고액 결제 | MEDIUM | amount_krw; user_avg_amount_prev; user_median_amount_prev; amount_ratio; amount_zscore | AI + Policy |
| F02 | 단시간 반복 결제 | HIGH | txn_count_10m; txn_count_1h_calc; txn_count_24h_calc; high_velocity | Rule + AI |
| F04 | 신규 계정 이상 결제 | MEDIUM | user_account_age_days; is_fresh_account; amount_ratio; ip_risk_score | Rule + AI |
| F06 | 고위험 IP 결제 | HIGH | ip_risk_score; amount_ratio; shipping_billing_match | UCP Signal + Risk Service + AI |
| F08 | 배송지/청구지 불일치 | MEDIUM | shipping_billing_match; city_match; ip_risk_score | Rule + AI |

---

## 9. 3단계 시연 시나리오

### 1단계 — 정상 거래

**F00 정상 거래**

```text
정상 금액
+ 정상 거래 빈도
+ 낮은 IP Risk
+ 주소 정보 정상
→ Final Risk Level: LOW
→ 정상 결제
```

### 2단계 — 중간 위험 거래

**F01 + F04**

```text
비정상 고액 결제
+ 신규 계정
→ Final Risk Level: MEDIUM
→ 추가 인증
```

### 3단계 — 고위험 복합 거래

**F01 + F02 + F06 + F08**

```text
비정상 고액
+ 단시간 반복 결제
+ 고위험 IP
+ 배송지/청구지 불일치
→ Final Risk Level: HIGH
→ 결제 거부
```

이 시연은 특정 Feature 하나만으로 거래를 거부하는 것이 아니라, 결제 과정에서 수집된 여러 위험 신호를 최종적으로 종합하여 Risk Score가 변화하는 구조를 보여주는 것을 목적으로 한다.

---

## 10. 완료 기준 검증

| 완료 기준 | 결과 |
|---|---|
| 이상거래 시나리오 10개 이상 | **충족 — 15개** |
| MCP 특화 시나리오 5개 이상 | **충족 — F11~F15 5개** |
| LOW / MEDIUM / HIGH 3단계 위험도 정의 | **충족** |
| UCP / Rule / AI 대응 여부 100% 분류 | **충족 — `fraud_scenario_matrix.csv` 참조** |
| AI MVP 탐지 대상 5개 이상 선정 | **충족 — F01, F02, F04, F06, F08** |

---

## 11. 산출물

- `fraud_scenario.md` — 정상 거래, 이상거래, MCP 특화 시나리오 및 AI MVP 정의
- `fraud_scenario_matrix.csv` — 시나리오별 위험도, Dataset 지원 여부, UCP / Rule / AI 대응 방식 정리

### 데이터 근거
본 문서의 Dataset 지원 여부와 Feature명은 프로젝트에 제공된 `dataset_description.md`의 UAE E-Commerce Fraud Synthetic Dataset 설명을 기준으로 작성하였다.