# 최종 Dataset 선정 결과

작성일: 2026-09-08

## 1. 최종 선정

### 실제 모델 학습 Dataset

**`fraud_model_features_erd_common.csv`**를 Payment MCP 이상거래 탐지 모델의 최종 학습 Dataset으로 선정한다.

파일: [`fraud_model_features_erd_common.csv`](./fraud_model_features_erd_common.csv)

| 항목 | 값 |
|---|---:|
| Rows | 100,000 |
| 입력 Feature | 27개 |
| Target | `is_fraud` |
| 정상 거래 | 91,789 |
| Fraud 거래 | 8,211 |
| Fraud Ratio | 8.211% |
| 결측값 | 0개 |

이 파일은 기존 `fraud_model_features.csv`의 77개 입력 Feature 중 최종 Pay ERD에서 확보·파생할 수 있는 27개만 남긴 프로젝트용 학습 데이터다. 원본을 그대로 변경하지 않고 별도 파일로 생성했다.

### 공개 기준 Dataset

공개 원본 후보로는 **Fraud ecommerce**를 선정한다.

로컬 파일 [`Fraud_Data.csv`](./Fraud_Data.csv)는 `user_id`, `signup_time`, `purchase_time`, `purchase_value`, `class`를 포함해 사용자·계정 연령·거래 시간·금액 중심의 모델을 만들기 쉽다. 다만 공개 원본의 라이선스가 명시되어 있지 않으므로, 외부 배포나 상업적 사용 시에는 별도 권리 확인이 필요하다.

## 2. 선정 근거

### 2.1 ERD와의 Feature 의미가 일치한다

최종 모델 Feature는 Pay ERD와 다음처럼 연결할 수 있다.

| 모델 Feature | ERD·서비스 원천 |
|---|---|
| `amount_krw`, `log_amount`, `amount_clipped` | `authorization_sessions.amount_minor`, `currency` |
| 사용자 평균·중앙값·표준편차 | `authorization_sessions.user_id`, `created_at`, `amount_minor`의 이전 이력 |
| `txn_count_10m`, `txn_count_1h_calc`, `txn_count_24h_calc` | `authorization_sessions.user_id`, `created_at` |
| `kst_hour`, `day_of_week`, `is_night` | `authorization_sessions.created_at` |
| `user_account_age_days`, `is_fresh_account` | 별도 계정 정보의 `accountCreatedAt` + `user_id` |
| `payment_method_*` | `selected_payment_method_id` → `payment_methods.provider` |

모델에는 `user_id`나 원본 시각을 직접 입력하지 않는다. 해당 값은 사용자별 이력과 시간 Feature를 계산하는 조회 키로만 사용한다.

### 2.2 최종 공격 시나리오를 학습할 수 있다

현재 Dataset은 다음 AI 시나리오에 필요한 Feature를 포함한다.

- F01 비정상 고액·금액 편차
- F02 단시간 반복 결제
- F03 비정상 시간대 결제
- F04 신규 계정 이상 결제
- F16 결제수단 유형 기반 이상 결제

F11 승인 금액 변경, F12 Checkout 변경, F13 Merchant 변경, F14 중복·Credential 재사용과 같은 ERD 보안 공격은 모델 입력 Feature보다 Rule 검증에 적합하므로 별도 Synthetic Rule 평가셋으로 보완한다.

### 2.3 공개 Dataset보다 재현 환경이 명확하다

ULB는 `Amount`와 익명 PCA Feature 중심이고 사용자·결제수단 정보가 없다. IEEE-CIS는 Feature가 많지만 대부분 익명화되어 Pay ERD와 의미를 직접 맞추기 어렵다. PaySim은 모바일 송금 데이터로 Merchant Checkout과 도메인이 다르다.

Fraud ecommerce는 네 후보 중 사용자 ID, 가입 시각, 구매 시각, 구매 금액을 모두 제공해 구조적 유사성이 가장 높다. 그러나 실제 서비스 입력은 공개 원본의 컬럼명이 아니라 Pay ERD의 승인 세션과 결제수단에서 생성해야 한다. 따라서 공개 Dataset을 그대로 최종 모델 입력으로 쓰는 대신, ERD 공통 Feature로 변환한 `fraud_model_features_erd_common.csv`를 최종 학습물로 사용한다.

### 2.4 불필요한 Feature와 현재 미확정 Feature를 제거했다

다음 Feature는 최종 Dataset에서 제외했다.

- IP 위험도·IP 국가
- 카드 국가·카드 연령·Card Present
- 사용자 Chargeback·고위험 사용자 상태
- 브라우저·디바이스·이메일 도메인
- 상품 수량·상품당 금액
- 배송지·청구지 불일치

상품·주소 Feature는 `canonical_checkout_json` 내부 Schema가 확정되면 별도 버전에서 추가할 수 있다.

## 3. Synthetic Data 보완 계획

공개 Dataset과 ERD 공통 Dataset만으로는 승인 증표와 결제 실행 보안 공격을 충분히 표현할 수 없다. 아래 시나리오는 Synthetic Data로 별도 생성한다.

| 시나리오 | 생성할 변형 | 사용 테이블·필드 |
|---|---|---|
| F11 승인 금액 변경 | 승인 금액과 결제 금액 불일치 | `authorization_sessions.amount_minor`, `payment_mandates.amount_minor`, `payments.amount_minor` |
| F12 Checkout 변경 | Checkout Digest 또는 요청 Hash 불일치 | `checkout_digest`, `canonical_checkout_json`, `request_hash` |
| F13 Merchant 변경 | 다른 Merchant에서 증표 사용 | `merchant_id`, `merchant_account_id`, Mandate binding |
| F14 중복·재사용 결제 | 동일 Idempotency Key 또는 Credential 재사용 | `idempotency_key`, `payment_attempts`, `consumed_at` |
| F17 만료 증표 사용 | 만료·소비된 Mandate/Token 사용 | `expires_at`, `status`, `consumed_at` |
| F19 인증 우회 | 인증 완료 전 결제 실행 | `required_action`, `approved_at`, biometric challenge 상태 |

ERD 공격 Synthetic Data는 우선 Rule 테스트셋으로 운영한다. AI 학습에 혼합할 때는 실제 행동 데이터와 동일한 분포인지 확인하고, Rule 위반 여부만으로 Target이 결정되는 데이터 Leakage를 방지한다.

## 4. 학습 전 필수 보정

현재 `fraud_model_features_erd_common.csv`에는 `user_account_age_days` 음수 값이 16,606건 존재한다. 계정 생성 정보를 수신하면 다음 두 Feature를 서비스 기준으로 재계산해야 한다.

```text
user_account_age_days = 결제 시각 - accountCreatedAt
is_fresh_account = user_account_age_days <= 정책 기준일
```

계정 생성일과 결제 시각은 UTC 또는 KST 중 하나로 통일하고, 사용자 이력·반복 결제 Feature는 현재 거래 이전의 데이터만 사용한다.

Fraud Ratio는 8.211%로 정상 거래보다 낮으므로 Stratified Split을 사용하고, Accuracy보다 PR-AUC, Recall, Precision, F1 및 운영 임계값별 False Positive를 함께 평가한다.

## 5. 최종 결론

공개 비교용 기준 Dataset은 **Fraud ecommerce**로 선정하고, 실제 서비스 모델 학습에는 **`fraud_model_features_erd_common.csv`**를 사용한다.

이 선택은 다음 조건을 만족한다.

1. 최종 Pay ERD에서 재현 가능한 Feature만 사용한다.
2. F01~F04 및 결제수단 기반 AI 시나리오를 하나의 학습 데이터 구조로 구성한다.
3. ERD 전용 공격은 Synthetic Rule 평가셋으로 분리해 보완한다.
4. 계정 생성 정보가 추가되면 신규 계정 Feature를 서비스 데이터와 동일하게 재계산할 수 있다.

## 참고 자료

- [Kaggle - Credit Card Fraud Detection](https://www.kaggle.com/mlg-ulb/creditcardfraud/home)
- [Amazon Fraud Dataset Benchmark README](https://github.com/amazon-science/fraud-dataset-benchmark/blob/main/README.md)
- [Kaggle - IEEE-CIS Fraud Detection Rules](https://www.kaggle.com/competitions/ieee-fraud-detection/rules)
- [Kaggle - PaySim Metadata](https://www.kaggle.com/datasets/ealaxi/paysim1/metadata)
- [PaySim dataset description](https://pmc.ncbi.nlm.nih.gov/articles/PMC7956727/)