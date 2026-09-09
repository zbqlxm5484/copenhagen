# Internal API Contract

우리 서비스 사이의 내부 HTTP 계약과 전송 DTO를 공유하는 순수 Java 라이브러리입니다.

넣을 수 있는 것:

- 내부 API request/response DTO
- endpoint와 오류 형식 계약
- 계약에서 사용하는 enum, schema, validation 지원 코드

넣으면 안 되는 것:

- Controller와 HTTP client 구현
- JPA Entity, Repository, Domain/Application Service
- 특정 애플리케이션의 비즈니스 규칙

계약 변경 시 제공자와 소비자 테스트를 함께 수정하고, 호환성이 깨지면 `docs/api/`와 ADR에 전환 방법을 기록합니다.

## Fraud 공통 데이터 계약

`com.copenhagen.contract.internalapi.fraud`는 Core의 향후 Intent Analyzer와 Pay의 Sift·Rule·Risk Engine이
주고받을 불변 데이터 모델입니다. 서비스의 Entity나 정책 구현을 공유하지 않습니다. 런타임 의존성이 없는
Java 21 record/enum이며 생성자에서 필수값·범위·컬렉션 불변성만 검증합니다.

요구사항의 `snake_case` 필드명은 기존 Java/JSON DTO 규칙에 따라 `camelCase`로 대응합니다.
예를 들어 `intent_id → intentId`, `max_total_amount → maxTotalAmount`,
`intent_analysis → intentAnalysis`입니다. 전역 JSON naming 설정은 변경하지 않습니다.

| 모델 | 필드와 타입 |
| --- | --- |
| `PurchaseIntent` | `intentId, userId: String`; `productKeywords: List<String>`; `category, brand: String?`; `quantity: Integer?`; `maxTotalAmount: Long?`; `currency, purpose: String?`; `allowedMerchants, constraints: List<String>`; `createdAt: Instant`; `expiresAt: Instant?` |
| `IntentAnalysisResult` | `intentMatch: Boolean`; `semanticRisk: RiskLevel`; `violations, warnings, reasonCodes: List<String>`; `summary: String` |
| `SiftFraudResult` | `available: Boolean`; `score: BigDecimal?`; `riskLevel: RiskLevel?`; `reasons: List<String>`; `workflowDecision, rawReferenceId: String?`; `latencyMs: Long?` |
| `RuleResult` | `ruleId: String`; `passed: Boolean`; `severity: RuleSeverity`; `reason: String` |
| `FraudDecision` | `decision: FraudDecisionType`; `riskLevel: RiskLevel`; `sift: SiftFraudResult`; `intentAnalysis: IntentAnalysisResult?`; `rules: List<RuleResult>`; `reasonCodes: List<String>`; `humanReadableReason: String`; `evaluatedAt: Instant`; `traceId: String` |

`?`는 null을 허용한다는 뜻이며 Java `Optional` 필드가 아닙니다. `Boolean` 필드는 null을 거부합니다.
직렬화 입력에서 필드가 누락됐을 때 임의의 false로 바뀌지 않도록 primitive 대신 사용합니다.

- enum은 `FraudDecisionType = ALLOW / REQUIRE_AUTH / DENY`,
  `RiskLevel = LOW / MEDIUM / HIGH / CRITICAL`, `RuleSeverity = LOW / MEDIUM / HIGH / CRITICAL`입니다.
  숫자 ordinal 대신 이름을 전달합니다.
- `maxTotalAmount`는 통화의 최소 단위 정수입니다. 값이 있다면 음수가 아니어야 하고 유효한 대문자 ISO 4217 통화가
  함께 있어야 합니다. KRW 100000은 100000원, USD 10000은 100달러입니다.
- 수량이 있다면 양수, 만료 시각이 있다면 생성 시각보다 뒤여야 합니다. 현재 시각을 내부에서 읽거나 자동 생성하지 않습니다.
- 의도에 명시되지 않은 예산·수량·판매처·만료를 임의 기본값으로 채우지 않습니다. null/빈 목록은 미지정이며
  무제한 구매나 사용자 승인을 의미하지 않습니다. `constraints`는 자유 서술 제약의 문자열 목록입니다.
- 컬렉션의 null은 빈 목록으로 정규화하고 방어적 복사합니다. null 원소·빈 문자열 원소는 거부합니다.
- Sift 점수는 API의 `0..1` 범위입니다. Console의 `0..100`과 혼용하지 않습니다.
  공식 문서는 API 0.5가 Console 50에 해당함을 명시합니다.
  [Sift Score API](https://developers.sift.com/docs/v204/curl/score-api)
- `available=false`일 때 `score/riskLevel/workflowDecision`은 null이어야 합니다. 오류나 미실행을 0점/LOW로
  치환하지 않습니다. `available=true`이면 score가 필요하고, riskLevel은 향후 정책에서 분류하기 전까지 null일 수 있습니다.
- `workflowDecision`은 Sift의 사용자 정의 결정 식별 문자열이며 로컬 ALLOW와 동일하지 않습니다.
  `rawReferenceId`에는 외부 기록의 참조 ID만 두고 raw payload를 담지 않습니다.
- 분석이 없으면 `FraudDecision.intentAnalysis=null`, Sift 결과가 없으면 `available=false` 객체로 표현합니다.
  결측 결과를 어떻게 처리할지는 향후 Risk Engine의 책임이며 이 계약은 결정을 계산하거나 자동 ALLOW를 만들지 않습니다.
- 이 `FraudDecision`은 평가 결과 DTO입니다. PG 실행권, 승인/checkout revision 결합, 만료된 결정 검사는
  향후 Pay 내부 구현의 책임입니다. 카드번호·CVC·비밀번호 필드는 없습니다.

설정은 각 소유 서비스에 있습니다. Core는 `OpenAiFraudProperties`, Pay는 `SiftFraudProperties`와
`FraudDetectionProperties`를 사용합니다. 루트 [.env.example](../../../.env.example)에 요청된 환경변수명을 정의했습니다.
Sift 임계값은 숫자 기본값 없이 두 값 모두 미설정으로 둘 수 있으며, 설정할 때에는
`0 <= reviewThreshold < denyThreshold <= 1`을 만족해야 합니다.

이번 단계에는 API 호출·Rule/Risk Engine·결제 gate·DB 저장이 없습니다. 키/계정/임계값이 빈 상태로도
설정과 schema를 검증할 수 있지만, 향후 실제 연동에서 필요한 설정과 증거의 누락을 승인으로 처리해서는 안 됩니다.
`FRAUD_DETECTION_ENABLED`는 값만 바인딩하며 현재 결제 동작을 변경하지 않습니다.

단위 테스트는 같은 모듈에서 실행합니다.

```text
.\gradlew.bat :libs:internal-api-contract:test
```

모델 검증·불변성·nullable 의미·중첩 JSON 왕복을 확인합니다. Jackson은 테스트 의존성으로만 사용합니다.
