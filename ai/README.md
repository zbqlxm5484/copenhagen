# AI 작업 공간

Payment MCP의 FDS 평가 모듈입니다. 구매 의도·주문·컨텍스트 입력 계약, 개별·통합 평가 결과 계약, 환경설정 로더와 단위·계약 통합 테스트를 제공합니다.

## 개발 환경

- Python 3.11 이상: FDS 모듈 실행 환경입니다. 로컬 검증은 Python 3.11.9에서 수행합니다.
- pip와 venv: `ai/.venv`에 프로젝트와 의존성을 설치합니다.
- Pydantic: 타입, 필수값, 금액·통화 및 입력 간 참조 관계를 검증합니다.
- iso4217: 실제 ISO 4217 코드와 최소 통화 단위가 정의되어 있는지 확인합니다.
- pytest: 외부 서비스 없이 입력 계약을 검증합니다.

직접 사용하는 패키지 버전은 `pyproject.toml`에 고정되어 있습니다. 전이 의존성은 pip가 해당 패키지의 요구 범위에서 선택합니다.

저장소 루트에서 가상환경을 만든 후 **ai 디렉터리에서** 설치·테스트 명령을 실행합니다. PowerShell에서 가상환경 활성화 없이 실행할 수 있습니다.

```powershell
python -m venv ai/.venv
cd ai
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m pip check
```

테스트 도구가 필요 없는 환경에서는 `requirements.txt`를 사용합니다. Linux/macOS에서는 가상환경의 Python 경로가 `.venv/bin/python`입니다.

## 입력 계약 사용

```python
from copenhagen_fds.contracts import FraudEvaluationRequest

# BE 어댑터에서 전달한 dict 또는 JSON 문자열을 검증합니다.
request = FraudEvaluationRequest.model_validate(payload)
request_from_json = FraudEvaluationRequest.model_validate_json(json_payload)
```

전체 입력 예시는 `tests/test_input_contracts.py`의 `payload` fixture에 있습니다. 테스트 값은 모두 합성 데이터입니다.

| 모델 | 주요 내용 |
| --- | --- |
| `FraudEvaluationRequest` | `intent`, `order`, `context`를 묶는 평가 입력 |
| `PurchaseIntent` | 구매 요청 ID, 의도 원천·버전, 상품·수량·판매처, 예산, 명시 조건·선호·모호한 항목 |
| `IntentBudget` | 금액과 `maximum` 또는 `approximate` 구분 |
| `OrderSnapshot` | 구매 요청 ID, 스냅샷 참조, 판매처별 주문, 전체 결제액과 배송비·세금·할인 집계값 |
| `MerchantOrder` | 판매처 ID와 주문 ID, 상품 목록, 해당 주문의 결제액과 배송비·세금·할인 |
| `OrderItem` | 주문 안의 항목 ID, 상품 ID·이름·수량, 단가와 항목 결제액 |
| `SnapshotReference` | BE가 제공하는 스냅샷 ID, 문자열 버전, SHA-256 형식의 해시 |
| `EvaluationContext` | BE가 제공하는 평가 ID, 사용자 ID, 구매 요청 ID, 선택적인 승인 참조 |
| `ApprovalReference` | 승인 ID와 승인 대상 스냅샷 참조 |

### 금액과 미지정 값

모든 금액은 `{"amount_minor": 1000, "currency": "KRW"}`처럼 통화의 최소 단위로 전달합니다. KRW 1000은 1,000원, USD 1000은 10달러, KWD 1000은 1디나르입니다. 음수, 실수, 숫자 문자열, bool은 거부하며, 환산이나 반올림을 수행하지 않습니다. `XXX`, `XTS`, 귀금속 등 최소 통화 단위가 없는 코드는 거부합니다. 통화 코드의 형식 검증은 Pay 서비스의 결제 지원 통화 정책과 별개입니다.

`total`과 `line_total`은 BE가 전달한 최종 결제액입니다. `shipping`, `tax`, `discount`는 그 결제액을 설명하는 별도 구성 정보이며 자동으로 다시 더하거나 빼지 않습니다. 전체 스냅샷의 구성 정보는 전체 구매에 대한 집계값이고, 판매처별 구성 정보는 해당 주문의 값입니다. 값을 서로 합산하거나 임의로 배분하지 않습니다.

미지정 예산·수량·조건·배송비·세금·할인은 `None`으로 유지합니다. 명시적인 `0`, 빈 목록과 구분하며 기본 수량 1이나 무제한 예산을 채우지 않습니다. `maximum`은 전달받은 의도의 상한 표현이고 `approximate`는 대략적인 예산 표현입니다. 어느 값도 결제 승인이나 권한이 되지 않습니다.

### 검증 범위

- 필수값, 엄격한 타입, 빈 문자열, 양의 정수 수량, 음수가 아닌 정수 금액을 검사합니다. 알 수 없는 필드는 거부합니다.
- 의도·주문·컨텍스트의 구매 요청 ID가 일치해야 합니다. 주문 ID는 스냅샷 안에서, 항목 ID는 개별 주문 안에서 유일해야 합니다. 한 구매 요청에 여러 판매처 또는 같은 판매처의 여러 주문이 올 수 있습니다.
- 주문·상품·구성 금액·예산의 통화가 달라 환산이 필요한 입력은 거부합니다.
- 의도 원천은 `user_direct`, `agent_summary`, `unknown` 중 전달받은 값으로 보존합니다. `user_direct`라는 값만으로 실제 사용자 발언인지 인증하지 않습니다.
- 해시는 SHA-256 문자열 모양만 검사합니다. 해시를 생성하거나 원본과 대조하지 않습니다. 버전은 숫자로 변환하지 않는 불투명 문자열입니다.
- 예산 초과, 금액 합계 불일치, 승인 참조와 현재 주문의 버전·해시 차이는 후속 분석에 필요한 입력으로 보존합니다. 이 단계에서 승인이나 위험 분류를 결정하지 않습니다.

검증 후 모델은 필드 재할당을 막고 목록을 튜플로 보존합니다. JSON 배열은 `model_validate`와 `model_validate_json` 모두에서 받을 수 있으며 직렬화하면 배열로 나갑니다. 검증이 필요한 외부 입력에 Pydantic의 검증 우회용 `model_construct` 또는 `model_copy(update=...)`를 사용하지 않습니다.

검증 오류의 문자열에는 입력값을 표시하지 않습니다. 오류 상세를 처리할 때도 `ValidationError.errors(include_input=False)`를 사용하고 원문 구매 입력이나 사용자 식별자를 로그에 기록하지 않습니다.

이 계약은 AI 모듈의 입력 모델입니다. 현재 Core·Pay와 연결된 API 계약이나 제공자·소비자 통합 검증을 의미하지 않습니다. 인증·승인 진위, DB 접근, 결제 실행 및 외부 서비스 호출은 포함하지 않습니다.

## 개별 평가 결과 계약

`IntentAnalysisResult`, `SiftFraudResult`, `RuleResult`는 모두 실행 상태, 평가 메타데이터, 사유 목록을 가집니다. JSON 문자열과 Python dict 양쪽에서 같은 문자열 Enum 값을 받습니다.

| 실행 상태 | 결과 필드 | 평가 시각 | 사유 |
| --- | --- | --- | --- |
| `SUCCESS` | 반드시 존재 | 필수 | 의미·위험이 UNKNOWN이면 필수 |
| `FAILED` | `None` | 필수 | 실패 코드 필수 |
| `NOT_RUN` | `None` | `None` | `NOT_EXECUTED` 필수 |

`IntentAnalysisResult.comparison`은 `MATCH`, `MISMATCH`, `UNKNOWN`을 구분합니다. UNKNOWN은 분석 실행에는 성공했지만 의미 비교가 불확실한 상태입니다. 실행 실패·미실행은 `comparison=None`으로 구분하며 bool이나 금융 사기 확률로 변환하지 않습니다. 성공 시 실제 사용한 모델·프롬프트 버전을 반드시 전달합니다.

`SiftFraudResult.score`는 `{"value": 0.2, "unit": "SIFT_PAYMENT_ABUSE_0_1"}` 형태입니다. [Sift Score API 공식 문서](https://developers.sift.com/docs/v204/curl/score-api)의 `payment_abuse` API 점수 범위인 0.0~1.0을 그대로 보존합니다. 콘솔 표시용 0~100 값은 받지 않고 확률로 재해석하지 않습니다. 실패·미실행 시 점수는 `None`이며 0점으로 채우지 않습니다. 제공되지 않은 Sift 모델 버전도 `None`으로 유지합니다.

`RuleResult`는 규칙 ID와 `LOW`, `MEDIUM`, `HIGH`, `UNKNOWN` 중 평가자가 전달한 위험 수준을 보존합니다. 성공 시 적용한 정책 버전이 필요합니다. 이 모델은 점수·규칙·임계값을 계산하지 않습니다.

`EvaluationMetadata`에는 아래 정보가 연결됩니다.

- `target`: 평가·사용자·구매 요청 ID, 기존 `SnapshotReference`, `intent_version`. 입력의 `PurchaseIntent.version`이 미지정이면 의도 버전도 명시적으로 `None`으로 전달하며 새 버전을 만들어내지 않습니다.
- `trace_id`, `sources`: 추적 ID와 전달받은 데이터 출처. `BE`, `OPENAI`, `SIFT`, `RULE_ENGINE`, `FDS`, `MOCK`, `UNKNOWN`을 구분합니다. 출처 표기는 원천의 진위를 증명하지 않습니다.
- `evaluated_at`, `valid_until`: 시간대가 있는 ISO 날짜·시각. `valid_until`은 선택 사항이며 지정할 경우 평가 시각보다 늦어야 합니다. 실패·미실행에는 유효기간을 부여하지 않습니다.
- `model_version`, `prompt_version`, `policy_version`: 실제 적용한 버전. 필요한 버전을 임의 기본값으로 채우지 않습니다.

사유는 `EvaluationReason(code, source, reference_id)`로 표현합니다. 실패는 `EVALUATION_FAILED`, `TIMEOUT`, `INVALID_RESPONSE` 등 실행 실패 코드로 설명하고, 의미 불확실·데이터 부족·규칙 탐지는 별도 코드로 구분합니다. 외부 응답 전문이나 원문 사용자 입력을 사유에 넣지 않습니다.

## 통합 평가 결과 계약

`FraudDecision`은 개별 의도·Sift·규칙 결과, 정책에서 명시한 `required_components`, 위험 수준, FDS 권고, 확인 권고를 함께 받습니다. `required_components`는 생략할 수 없는 비어 있지 않은 목록이며, 모델이 임의로 필수 평가를 제외하지 않습니다.

- `SUCCESS`: 필수 평가가 성공하고 결과가 알려져 있어야 합니다. 위험 수준과 권고가 명시되어야 합니다. 정책에서 필수로 지정하지 않은 평가가 실패한 경우에도 그 실패 기록은 그대로 남습니다.
- `INCOMPLETE`: 필수 평가가 실패·미실행·UNKNOWN 또는 만료된 경우를 표현합니다. 위험은 `UNKNOWN`, 권고는 `None`으로 두며 사유를 전달합니다.
- `FAILED`: 통합 평가 자체의 실패입니다. 개별 결과가 성공했더라도 통합 권고는 만들지 않습니다. 실행 실패 사유가 필요합니다.
- `NOT_RUN`: 통합 평가와 포함된 개별 평가가 모두 미실행이어야 합니다.

통합 결과와 개별 결과의 평가 대상·주문 참조·Intent 버전·추적 ID가 다르면 거부합니다. 필수 결과의 유효기간이 정해져 있다면 통합 평가 시점에 유효해야 하고 통합 결과가 그 기간보다 오래 유효할 수 없습니다. 유효기간은 FDS 결과의 해당 대상에 대한 적용 범위이며 사용자 승인 만료 정책이 아닙니다. 기본 TTL이나 현재 시각을 모델에서 생성하지 않습니다.

`ALLOW`, `REQUIRE_AUTH`, `DENY`는 **FDS 평가 권고**입니다. 모델은 입력된 위험 수준과 권고를 형식 검증할 뿐 위험 분류 정책을 실행하지 않습니다. 사용자 승인 확인과 실제 추가 인증·결제 차단·결제 실행은 BE의 책임입니다. `review_actions`에는 `CHECK_INPUT`, `RETRY_EVALUATION`, `REVIEW_ORDER`, `VERIFY_USER_INTENT`를 전달할 수 있습니다.

개별 결과 예시는 `tests/conftest.py`와 `tests/test_result_contracts.py`, 통합 결과 예시는 `tests/test_decision_contracts.py`에 있습니다. 모두 합성 데이터로 실행하며 외부 API나 서비스 DB를 호출하지 않습니다.

### 입력과 평가 결과 연결

입력과 결과를 각각 역직렬화한 경우, 결과를 사용할 때 `validate_for_request`로 두 계약의 참조가 일치하는지도 검사합니다.

```python
from copenhagen_fds.contracts import FraudDecision, FraudEvaluationRequest

request = FraudEvaluationRequest.model_validate_json(request_json)
decision = FraudDecision.model_validate_json(decision_json)
decision.validate_for_request(request)
```

평가 ID·사용자 ID·구매 요청 ID, 현재 주문의 스냅샷 ID·버전·해시, 의도 버전을 비교합니다. 하나라도 다르면 입력값을 포함하지 않는 `ValueError`를 발생시키며, 일치하면 기존 결과 객체를 그대로 반환합니다. 입력의 의도 버전이 `None`이면 결과도 명시적인 `None`이어야 합니다. 개별 모델의 역직렬화만으로 이 연결 검사까지 자동 실행되지는 않습니다.

이 검사는 전달된 참조의 일치 여부만 확인합니다. 주문 내용의 해시를 재계산하거나 사용자·승인을 인증하지 않습니다. 이전 승인에 연결된 스냅샷은 분석용 입력으로 보존하고, 결과의 대상은 현재 주문과 비교합니다.

## 환경설정

`copenhagen_fds.settings.load_settings()`는 기존 Pydantic 모델과 표준 라이브러리로 프로세스 환경변수를 읽고 검증합니다. 반환하는 설정은 필드 재할당을 막습니다. 명시적인 매핑을 넘기면 해당 매핑만 사용하므로 테스트가 개발자 PC의 키나 환경설정에 의존하지 않습니다.

```python
from copenhagen_fds.settings import SettingsError, load_settings

settings = load_settings({})  # 외부 키 없이 독립적인 MOCK 설정
# 실제 실행 진입점에서는 load_settings()로 프로세스 환경변수를 읽습니다.

try:
    load_settings({"FDS_MODE": "LIVE"})
except SettingsError as error:
    print(error)  # 누락되거나 잘못된 설정 항목만 표시하며 입력값은 포함하지 않습니다.
```

| 환경변수 | 미지정 시 | 의미 |
| --- | --- | --- |
| `FDS_MODE` | `MOCK` | `MOCK` 또는 `LIVE`, 대소문자 구분 |
| `FDS_OPENAI_MODEL` | `gpt-5.4-mini` | 명시한 모델명 보존, 오류 시 다른 모델로 대체하지 않음 |
| `FDS_OPENAI_API_KEY` | `None` | OpenAI 키, LIVE 필수 |
| `FDS_SIFT_API_KEY` | `None` | Sift 키, LIVE 필수 |
| `FDS_OPENAI_TIMEOUT_SECONDS` | `None` | OpenAI 호출 제한 시간, 초 단위 |
| `FDS_SIFT_TIMEOUT_SECONDS` | `None` | Sift 호출 제한 시간, 초 단위 |
| `FDS_EVALUATION_TIMEOUT_SECONDS` | `None` | 전체 평가 제한 시간, 초 단위 |
| `FDS_POLICY_VERSION` | `None` | 명시적으로 적용할 정책 버전 |
| `FDS_SIFT_MEDIUM_THRESHOLD` | `None` | Sift 중간 위험 구간의 시작값 |
| `FDS_SIFT_HIGH_THRESHOLD` | `None` | Sift 높은 위험 구간의 시작값 |
| `FDS_SIFT_SCORE_UNIT` | `None` | 임계값을 지정하면 `SIFT_PAYMENT_ABUSE_0_1` 필수 |

- **MOCK**는 키·제한 시간·정책·임계값 없이 설정을 읽을 수 있습니다. 미지정 값은 그대로 `None`이며 운영값으로 채우지 않습니다.
- **LIVE**는 두 API Key, 세 제한 시간, 정책 버전, 두 임계값과 점수 단위를 모두 명시해야 합니다. 설정 로딩은 실제 API 호출이나 접근 권한 확인을 수행하지 않습니다. `LIVE` 설정이 유효해도 계정의 제품·sandbox 사용 권한을 보장하지 않습니다.
- 제한 시간은 유한한 양수이며 각 제공자 제한 시간은 전체 평가 제한 시간 이하여야 합니다. 실제 병렬·순차 실행과 취소 처리는 호출·통합 평가 구현에서 담당합니다.
- 임계값은 기존 `SiftScore` 계약을 재사용해 `0 <= medium < high <= 1`을 검증합니다. 두 값과 단위는 함께 설정하고, MOCK에서도 임계값을 주면 정책 버전이 필요합니다. 임계값으로 위험을 분류하는 알고리즘이나 금융 사기 확률 변환은 이 로더에 포함하지 않습니다.
- 선택 항목의 빈 문자열은 미지정으로 처리합니다. 모드·모델의 빈 문자열, 공백뿐인 값, 오타가 있는 `FDS_` 변수는 오류로 처리합니다. 무관한 프로세스 환경변수는 무시합니다.

키는 `SecretStr`로 보관해 `str`, `repr`, `model_dump_json`과 JSON 모드의 `model_dump`에서 마스킹됩니다. 제공자 클라이언트가 실제 호출할 때만 해당 키의 `get_secret_value()`를 사용하고 그 반환값을 로그나 문서에 넣지 않습니다. `load_settings`의 `SettingsError`는 변수 이름과 고정된 설명만 보존하며 원본 검증 오류나 입력값을 예외 체인에 연결하지 않습니다. 환경변수 입력의 검증 경계는 `load_settings`이고, 설정 모델의 저수준 생성·검증 API를 직접 사용할 필요는 없습니다.

`ai/.env.example`은 실제 키와 운영 임계값이 비어 있는 참조 예제입니다. 로더는 이 파일이나 루트 `.env`를 자동으로 읽지 않습니다. 필요한 값을 프로세스 환경변수 또는 실행 환경의 Secret 주입 기능으로 전달합니다. 읽기 예제는 다음처럼 MOCK 모드에서 실행할 수 있습니다.

```powershell
# ai 디렉터리에서 실행
$env:FDS_MODE = "MOCK"
.\.venv\Scripts\python.exe -c "from copenhagen_fds.settings import load_settings; print(load_settings())"
```

설정 검증 예시는 `tests/test_settings.py`에 있습니다. 예제와 테스트에 쓰는 문자열은 합성 데이터이며 실제 OpenAI·Sift에 연결하지 않습니다.

## 계약 통합 테스트

`tests/contract/`는 입력·개별 결과·통합 결과·MOCK 설정을 함께 사용합니다. 정상, 여러 판매처, 의미 불확실, 외부 평가 실패, 미수집, 미실행 상황의 합성 데이터로 JSON·dict 역직렬화와 JSON 왕복 변환을 검증합니다. 테스트 내부의 요청·결과 묶음은 테스트용 포맷이며 외부 API 명세가 아닙니다.

```powershell
# ai 디렉터리에서 실행
.\.venv\Scripts\python.exe -m pytest tests/contract -q
```

금액·통화·버전·상태·시각·주문 관계를 보존하고, 다른 요청의 결과를 연결하거나 UNKNOWN을 bool로 바꾸거나 실패 점수를 0으로 채운 경우를 검사합니다. 설정은 명시적인 매핑으로 주입하며 실제 키나 `.env`에 의존하지 않습니다. 소켓 연결을 시도하면 테스트가 실패합니다.

큰 정수 보존은 Python의 JSON 처리 경계에서 검증합니다. JavaScript·BE의 숫자 범위와 전송 포맷 호환성, 실제 Core·Pay 제공자·소비자 연동은 이 테스트의 검증 범위에 포함되지 않습니다.

## 디렉터리

```text
ai/
├── .env.example              # 키·운영 정책값이 비어 있는 환경변수 참조 예제
├── pyproject.toml            # 패키지, 의존성, pytest 설정
├── requirements*.txt        # pip 설치 진입점
├── src/copenhagen_fds/
│   ├── contracts/           # FDS 입력·결과·상태 모델과 공통 값 타입
│   └── settings.py          # 환경변수 로더, 설정 검증과 Secret 보호
├── tests/                   # 합성 데이터 기반 단위 테스트
│   └── contract/           # 입력·결과·설정의 연결과 JSON 왕복 검증
├── config/                  # 설정 작업 공간
├── data/                    # 로컬 데이터, Git 제외
├── docs/                    # 기존 데이터셋 검토 문서
├── models/                  # 모델 관련 산출물 작업 공간
└── results/                 # 평가 결과 작업 공간
```

가상환경과 Python 생성 파일은 `ai/.gitignore`로 제외합니다. 비밀키, 개인정보, 대용량 데이터 및 개인 기획 문서는 저장소에 추가하지 않습니다.
