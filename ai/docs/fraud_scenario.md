# AI 이상거래 탐지 시나리오 정의서

작성일: 2026-09-08

## 1. 문서 목적

Payment MCP에서 발생할 수 있는 정상 결제와 이상거래를 정의하고, 각 시나리오의 위험도와 처리 주체를 확정한다.

본 문서는 시연 화면에 직접 노출하는 자료가 아니라, AI 학습 Feature·ERD Rule·Risk Level·PPT 설명의 기준으로 사용한다. 시연 PPT에서는 개별 시나리오 목록을 나열하기보다 결제 정보 수집 → Feature 계산 → Rule 검증 → AI Risk Score → 최종 대응의 흐름을 보여준다.

## 2. 적용 범위와 데이터 기준

- 최종 AI 학습 데이터: `fraud_model_features_erd_common.csv`
- 학습 데이터 구성: 100,000행, 입력 Feature 27개, Target `is_fraud`
- 최종 Pay ERD: Authorization Session, Payment Method, Payment, Mandate, Payment Credential, Payment Attempt, Idempotency Record 중심
- 계정 정보: 별도 Account/User 서비스에서 `userId`와 `accountCreatedAt`을 제공
- AI 모델은 ERD와 서비스에서 공통으로 확보하거나 결정적으로 파생할 수 있는 Feature만 사용
- 명확한 무결성 위반은 AI보다 Policy / Rule 계층에서 먼저 차단
- `canonical_checkout_json` 내부의 상품·주소 Schema가 확정되기 전까지 상품·주소 Feature는 학습 Feature에서 제외

### 2.1 최종 AI 입력 Feature

| Feature 영역 | 사용 Feature | ERD·서비스 원천 |
|---|---|---|
| 금액 | `amount_krw`, `log_amount`, `amount_clipped` | `authorization_sessions.amount_minor`, `currency` |
| 금액 이력 | `user_avg_amount_prev`, `user_median_amount_prev`, `user_std_amount_prev`, `user_avg_amount_30d`, `user_txn_count_prev` | `user_id`, `created_at`, `amount_minor` 이전 이력 |
| 금액 편차 | `amount_ratio`, `amount_deviation`, `amount_zscore` | 현재 금액과 사용자 이전 이력 |
| 거래 빈도 | `txn_count_10m`, `txn_count_1h_calc`, `txn_count_24h_calc` | `user_id`, `created_at` 시간 윈도우 |
| 시간 | `kst_hour`, `day_of_week`, `is_weekend`, `is_night`, `user_avg_hour_prev`, `hour_deviation` | `authorization_sessions.created_at` |
| 계정 연령 | `user_account_age_days`, `is_fresh_account` | `accountCreatedAt` + 결제 시각 |
| 결제수단 | `payment_method_apple_pay`, `payment_method_bank_transfer`, `payment_method_card`, `payment_method_google_pay`, `payment_method_paypal` | `selected_payment_method_id` → `payment_methods.provider` |

`userId`와 원본 시각은 모델에 직접 넣지 않는다. 사용자 이력, 거래 빈도, 시간대, 계정 연령을 계산하기 위한 조회·파생 원천으로만 사용한다.

## 3. 처리 구조

```text
결제 요청
  ↓
Authorization Session 및 Checkout 검증
  ↓
사용자 이력·계정 생성일·결제수단 조회
  ↓
ERD 무결성 Rule 검사
  ↓
27개 AI Feature 계산
  ↓
Fraud Detection AI Risk Score 산출
  ↓
Policy Engine에서 최종 위험도 결정
  ↓
LOW: 결제 진행 / MEDIUM: 추가 인증 / HIGH: 결제 차단
```

## 4. 위험도 정의

| 위험도 | 의미 | 기본 대응 |
|---|---|---|
| LOW | 단독으로 Fraud를 의미하기 어려운 보조 신호 | 결제 진행, Risk Score에 낮은 가중치 반영 |
| MEDIUM | 정상 상황에서도 발생할 수 있으나 추가 확인이 필요한 신호 | 추가 인증 또는 보류 |
| HIGH | Fraud 가능성이 높거나 명확한 무결성·정책 위반 | 결제 차단 또는 강한 인증 |

수치 Threshold는 현재 고정하지 않는다. 학습·검증 데이터의 Precision/Recall과 서비스 오탐 비용을 반영해 별도 정책으로 확정한다.

## 5. 최종 활성 AI 시나리오

### F01 — 비정상 고액·금액 편차

| 항목 | 내용 |
|---|---|
| 위험도 | MEDIUM |
| 처리 주체 | AI + Policy |
| 탐지 내용 | 현재 결제금액이 사용자의 과거 평균·중앙값·표준편차에서 크게 벗어나는 거래를 탐지한다. |
| 주요 Feature | `amount_krw`, `user_avg_amount_prev`, `user_median_amount_prev`, `amount_ratio`, `amount_deviation`, `amount_zscore` |
| ERD 원천 | `authorization_sessions.user_id`, `amount_minor`, `currency`, `created_at` 및 이전 승인 세션 |
| 판단 | 금액 편차만으로 즉시 거부하지 않고 신규 계정·반복 결제 등과 함께 Risk Score를 높인다. |
| 대응 | MEDIUM이면 추가 인증, 정책상 절대 한도 초과는 Policy에서 별도 차단 |

### F02 — 단시간 반복 결제

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Rule + AI |
| 탐지 내용 | 동일 사용자의 결제 승인 세션이 10분·1시간·24시간 구간에 비정상적으로 집중되는 상황을 탐지한다. |
| 주요 Feature | `txn_count_10m`, `txn_count_1h_calc`, `txn_count_24h_calc` |
| ERD 원천 | `authorization_sessions.user_id`, `created_at` |
| 판단 | 명확한 요청 폭주는 Rate Limit Rule로 먼저 제한하고, 정상적인 반복 구매와 공격성 반복을 AI가 구분한다. |
| 대응 | 반복 기준 초과 시 추가 인증 또는 결제 차단 |

### F03 — 비정상 시간대 결제

| 항목 | 내용 |
|---|---|
| 위험도 | LOW |
| 처리 주체 | AI 보조 신호 |
| 탐지 내용 | 사용자의 평소 결제 시간대와 현재 결제 시간이 크게 다른 거래를 탐지한다. |
| 주요 Feature | `kst_hour`, `day_of_week`, `is_weekend`, `is_night`, `user_avg_hour_prev`, `hour_deviation` |
| ERD 원천 | `authorization_sessions.created_at` |
| 판단 | 야간·주말 결제 자체는 정상일 수 있으므로 단독 차단하지 않는다. |
| 대응 | 고액·반복·신규 계정 신호가 함께 있으면 Risk Score를 보조적으로 상승 |

### F04 — 신규 계정 이상 결제

| 항목 | 내용 |
|---|---|
| 위험도 | MEDIUM |
| 처리 주체 | AI + Policy |
| 탐지 내용 | 계정 생성 직후 고액 또는 반복 결제가 발생하는 패턴을 탐지한다. |
| 주요 Feature | `user_account_age_days`, `is_fresh_account`, `amount_ratio`, `amount_zscore`, `txn_count_10m` |
| ERD·서비스 원천 | `userId`, 별도 계정 정보의 `accountCreatedAt`, `authorization_sessions.created_at` |
| 판단 | 신규 계정 자체를 Fraud로 보지 않고 금액·속도 편차와 결합한다. |
| 대응 | MEDIUM이면 추가 인증, 정책상 신규 계정 제한 조건은 Policy로 처리 |
| 전제 | 계정 생성 정보 수신 후 `user_account_age_days`와 `is_fresh_account`를 서비스 기준으로 재계산 |

### F16 — 결제수단 유형 기반 이상 결제

| 항목 | 내용 |
|---|---|
| 위험도 | MEDIUM |
| 처리 주체 | AI 보조 신호 |
| 탐지 내용 | 결제수단 Provider 유형별 거래 패턴을 학습하여 특정 결제수단과 금액·시간·거래 빈도의 조합이 Fraud 패턴과 유사한지 판단한다. |
| 주요 Feature | `payment_method_apple_pay`, `payment_method_bank_transfer`, `payment_method_card`, `payment_method_google_pay`, `payment_method_paypal` |
| ERD 원천 | `authorization_sessions.selected_payment_method_id` → `payment_methods.provider` |
| 판단 | Provider 유형만으로 결제수단 탈취나 신규 수단 사용을 확정하지 않는다. |
| 대응 | 다른 AI 신호와 결합하여 추가 인증 또는 Risk Score 상승 |
| 전제 | ERD Provider 값과 학습 데이터의 One-hot 범주를 동일한 규칙으로 매핑 |

## 6. 최종 활성 ERD Rule 시나리오

### F11 — 승인 금액 변경

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Policy / Rule |
| 탐지 내용 | 사용자가 승인한 금액과 Mandate·Credential·Payment에 기록된 금액이 달라진 경우를 탐지한다. |
| 검증 필드 | `authorization_sessions.amount_minor`, `payment_mandates.amount_minor`, `payment_credential_tokens.amount_minor`, `payments.amount_minor`, `currency` |
| 판단 | 승인 금액보다 최종 결제금액이 증가하면 AI 결과와 관계없이 결제를 중단한다. |
| 대응 | 결제 거부, 세션 무효화, 감사 로그 기록 |

### F12 — 승인 후 Checkout 변경

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Policy / Rule |
| 탐지 내용 | 사용자 승인 이후 Checkout 내용이나 금액이 바뀌어 Checkout Digest 또는 Request Hash가 일치하지 않는 경우를 탐지한다. |
| 검증 필드 | `checkout_digest`, `canonical_checkout_json`, `pay_verified_checkout_evidence.signature_verified`, `request_hash` |
| 판단 | 서명·Digest 검증에 실패한 Checkout은 결제 실행 대상으로 인정하지 않는다. |
| 대응 | 결제 거부, 승인 세션 및 관련 Mandate 무효화 |

### F13 — 승인 후 Merchant 변경

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Policy / Rule |
| 탐지 내용 | 사용자가 승인한 Merchant와 실제 Payment·Mandate·Credential에 바인딩된 Merchant가 다른 경우를 탐지한다. |
| 검증 필드 | `authorization_sessions.merchant_account_id`, `merchant_accounts.merchant_id`, `payments.merchant_account_id`, `payment_mandates.merchant_id`, `external_checkout_id` |
| 판단 | Merchant Binding이 일치하지 않으면 Credential 탈취 또는 요청 변조로 간주한다. |
| 대응 | 결제 거부, Credential 폐기, 보안 이벤트 기록 |

### F14 — 중복·재요청 결제

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Idempotency / Policy / Rule |
| 탐지 내용 | 동일 주문 또는 동일 요청이 재전송되어 Payment가 중복 생성되거나 같은 결제 Credential이 재사용되는 상황을 탐지한다. |
| 검증 필드 | `payments.idempotency_key`, `payments.request_hash`, `idempotency_records`, `payment_attempts` |
| 판단 | 같은 Idempotency Key와 Request Hash에는 동일한 결과를 반환하고 새 결제를 생성하지 않는다. |
| 대응 | 중복 결제 차단, 기존 결과 반환, 시도 이력 기록 |

### F17 — 비활성·타인 결제수단 사용

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Policy / Rule |
| 탐지 내용 | 사용자가 소유하지 않았거나 비활성·삭제된 Payment Method를 이용해 결제를 시도하는 경우를 탐지한다. |
| 검증 필드 | `authorization_sessions.user_id`, `selected_payment_method_id`, `payment_methods.user_id`, `payment_methods.status`, `payment_methods.deleted_at` |
| 판단 | Payment Method 소유자와 Authorization Session의 사용자가 일치하고 상태가 사용 가능해야 한다. |
| 대응 | 결제 거부 및 인증·보안 이벤트 기록 |

### F18 — 만료·재사용된 Mandate 또는 Credential

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Policy / Rule |
| 탐지 내용 | 만료되었거나 이미 소비된 Checkout Mandate·Payment Mandate·Payment Credential을 다시 사용하는 공격을 탐지한다. |
| 검증 필드 | `checkout_mandates.status`, `checkout_mandates.expires_at`, `payment_mandates.status`, `payment_mandates.expires_at`, `payment_credential_tokens.status`, `expires_at`, `consumed_at` |
| 판단 | 만료 시각·상태·소비 여부를 모두 확인하고 일회성 Credential은 한 번만 사용할 수 있도록 한다. |
| 대응 | 결제 거부, Credential 폐기, 재사용 이벤트 기록 |

### F19 — 인증 미완료·우회 결제

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Authentication Policy / Rule |
| 탐지 내용 | 필요한 인증이 완료되지 않았거나 승인되지 않은 Authorization Session에서 결제가 실행되는 경우를 탐지한다. |
| 검증 필드 | `authorization_sessions.status`, `required_action`, `approved_at`, `rejected_at`, `expires_at`, biometric challenge 상태·`used_at`, PIN Credential 상태·`locked_until` |
| 판단 | 세션 승인 상태와 요구된 인증 결과가 모두 유효해야 Payment 실행을 허용한다. |
| 대응 | 결제 거부, 세션 무효화, 인증 실패 누적 및 감사 로그 기록 |

### F20 — 결제 시도 폭증·재시도 공격

| 항목 | 내용 |
|---|---|
| 위험도 | HIGH |
| 처리 주체 | Rule / Monitoring |
| 탐지 내용 | 하나의 Payment에 대해 짧은 시간 동안 결제 시도와 PG 재시도가 반복되거나 실패 코드가 누적되는 상황을 탐지한다. |
| 검증 필드 | `payment_attempts.attempt_no`, `operation`, `status`, `error_code`, `started_at`, `completed_at`, `pg_transactions.response_code`, `last_checked_at` |
| 판단 | 네트워크 장애에 의한 재시도와 공격성 반복을 구분하기 위해 재시도 횟수·간격·실패 코드를 함께 확인한다. |
| 대응 | 재시도 중단, 추가 인증 또는 일시 보류, 운영 알림 |

## 7. 보류·제외 시나리오

아래 시나리오는 Fraud 관점에서는 의미가 있지만 현재 최종 ERD와 학습 데이터의 공통 Feature가 아니므로 최종 AI 활성 목록에서 제외하거나 조건부로 보류한다.

| ID | 시나리오 | 위험도 | 상태 | 보류·제외 이유 |
|---|---|---|---|---|
| F05 | 과거 Chargeback 이력 + 현재 이상행동 | MEDIUM | 제외 | Chargeback 이력 테이블과 사용자 위험 상태가 없음 |
| F06 | 고위험 IP 결제 | HIGH | 제외 | IP 원천과 IP Reputation 정보가 없음 |
| F07 | 카드 국가 정보 불일치 | MEDIUM | 제외 | 카드 국가 Feature가 ERD에 없음 |
| F08 | 배송지·청구지 불일치 | MEDIUM | 보류 | `canonical_checkout_json` 내부 주소 Schema와 정규화 규칙 미확정 |
| F09 | 다수 고가 상품·상품당 금액 이상 | MEDIUM | 보류 | `items[]`, 수량, 단가 Schema 미확정 |
| F10 | 구매 카테고리 급변 | MEDIUM | 제외 | Merchant Category와 사용자별 장기 카테고리 이력이 없음 |
| F15 | Agent 과도한 구매 | HIGH | 제외 | 사용자 예산·요청 범위·최종 구매 범위의 구조화된 Context가 없음 |

Schema가 확정되면 F08·F09는 `canonical_checkout_json`에서 결정적으로 파생한 Feature를 추가하고, 학습 데이터 재생성 및 모델 재검증 후 활성화한다.

## 8. 정상 거래 기준

### F00 — 정상 결제

| 항목 | 내용 |
|---|---|
| 위험도 | LOW |
| 내용 | 현재 금액이 사용자 과거 패턴에서 크게 벗어나지 않고, 단시간 반복·비정상 시간·신규 계정·결제수단 이상 신호가 누적되지 않은 거래다. |
| 처리 | ERD 무결성 Rule을 통과하고 AI Risk Score가 LOW이면 정상 결제를 진행한다. |

신규 계정이나 야간 결제 자체는 정상일 수 있다. 단일 Feature만으로 Fraud를 확정하지 않고 여러 신호의 조합으로 최종 Risk를 산출한다.

## 9. 최종 시나리오 분류

| 처리 영역 | 최종 시나리오 | 목적 |
|---|---|---|
| AI 모델 | F01, F02, F03, F04, F16 | 사용자 행동·금액·시간·계정·결제수단 패턴의 확률적 탐지 |
| ERD Rule | F11, F12, F13, F14, F17, F18, F19, F20 | 승인·증표·인증·중복·재시도 무결성의 결정론적 차단 |
| 보류·제외 | F05, F06, F07, F08, F09, F10, F15 | 데이터 원천 또는 Schema 확보 후 재검토 |
| 정상 기준 | F00 | 정상 거래와 위험 신호 누적 구조의 기준 |

## 10. 운영상 주의사항

- 사용자별 과거 평균·표준편차·거래 빈도는 현재 결제보다 이전에 발생한 데이터만 사용한다.
- `amount_minor`와 `currency`를 모델의 `amount_krw`로 변환할 때 통화·환율·소수점 단위를 고정한다.
- 계정 생성 정보를 수신하면 `accountCreatedAt`과 `authorization_sessions.created_at`을 동일한 시간대 기준으로 변환해 `user_account_age_days`와 `is_fresh_account`를 재계산한다.
- 현재 가공 Dataset에는 `user_account_age_days` 음수 값이 있으므로 계정 생성 정보 연동 후 최종 학습 전에 재생성한다.
- `payment_methods.provider`의 실제 값과 학습 Dataset의 One-hot 범주를 동일하게 매핑한다.
- Rule 위반 Synthetic Data는 우선 별도 평가셋으로 관리한다. Rule 조건만으로 생성한 데이터를 AI 학습 데이터에 그대로 섞으면 모델이 행동 기반 Fraud보다 특정 무결성 조건만 학습할 수 있다.
- Fraud Ratio가 8.211%이므로 Accuracy만 사용하지 않고 PR-AUC, Precision, Recall, F1 및 운영 Threshold별 False Positive를 함께 평가한다.

## 11. 최종 결정

최종 AI 모델은 **F01·F02·F03·F04·F16**을 사용하고, 결제 실행 전후의 무결성·인증 공격은 **F11·F12·F13·F14·F17·F18·F19·F20 Rule**로 처리한다.

F04는 별도 계정 정보의 `accountCreatedAt`을 수신한 뒤 활성화한다. F08·F09는 Checkout JSON Schema가 확정될 때까지 보류한다. 본 시나리오 정의를 기반으로 PPT에서는 각 Feature가 Risk Score와 최종 결제 정책에 반영되는 흐름을 설명한다.