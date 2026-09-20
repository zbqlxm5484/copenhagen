# 검증 기록

## 2026-09-20 — 모노레포 mobile 이전

스토리 [S15P21A207-456](https://ssafy.atlassian.net/browse/S15P21A207-456)의 현재 Android 앱을 모노레포 `mobile/`로 이전한 뒤, 해당 위치에서 검증했다. 기존 샘플 프로젝트를 교체했으며 production Kotlin 20개, 리소스, JUnit 테스트 2개 파일과 검증 도구의 내용은 이전 전 소스와 동일하다.

실행 환경: JDK 21.0.11, Android SDK 35, Gradle 8.11.1. 의존성이 준비된 로컬 캐시를 사용해 다음 명령을 실행했다.

```sh
cd mobile
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 tools/audit.py
```

- Firebase·서버 로컬 설정이 없는 상태의 기본 빌드 성공.
- 기존 로컬 서버 설정과 Firebase 설정을 적용한 최종 빌드 성공. `processDebugGoogleServices` 실행 확인.
- debug APK 생성: `app/build/outputs/apk/debug/app-debug.apk`.
- JUnit 23개 통과: `HttpTest` 12개, `PayContractTest` 11개. 실패·오류·건너뜀 0개.
- Android Lint: 오류 0개, 경고 14개.
- 정적 검사 59개 통과.
- 저장소의 런타임 `data/` 제외 규칙이 앱 소스를 누락시키지 않도록 `mobile/.gitignore`에 Android `data` 패키지 예외를 추가했다.
- `local.properties`, `environment.properties`, `app/google-services.json`은 로컬에서만 보존하며 Git에 포함하지 않는다. 빌드 산출물·IDE 및 Gradle 캐시·서명 키도 제외한다.
- 실제 서버 E2E, Firebase 푸시 발송, Android Keystore·생체 인증 프롬프트와 실기기 화면 검증은 이번 이전 검증 범위에 포함되지 않는다. 남은 연동 조건은 스토리 및 기존 Jira 이슈에서 관리한다.

아래는 이전 소스에 포함되어 있던 과거 검증 기록이다. 테스트 수와 환경은 각 실행 시점을 기준으로 한다.

## 2026-09-17 — FCM 추가 및 초기 전달본 기록

### FCM 추가 후 검증

JDK 21, Android SDK 35에서 아래 명령을 실행했다. Firebase 설정 파일 없이 빌드했다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
python3 tools/audit.py
```

- debug APK 빌드 성공: `app/build/outputs/apk/debug/app-debug.apk`.
- 기존 JUnit 16개 통과: `PayContractTest` 8개, `HttpTest` 8개. FCM 실기기 테스트가 아니다.
- Android Lint 통과, 오류 0. 기존 코드와 라이브러리 버전 등의 경고는 남아 있다.
- 정적 검사 59개 통과, production Kotlin 20개 파일.
- Firebase FID 모드를 사용하므로 이전 토큰용 `onNewToken()` Lint 요구는 근거를 주석으로 남겨 해당 경고만 제외한다.
- 실제 Firebase 프로젝트 설정·FID 등록 API·푸시 발송·OS 권한 프롬프트·실기기 화면 진입은 미검증.
  `app/google-services.json`을 추가한 뒤 로그인, 전경/배경/종료 상태 알림 클릭, 알림 권한 거부를 검증해야 한다.

### 초기 전달본에서 수행한 검사

| 검사 | 결과 | 범위 |
|---|---|---|
| `tools/verify-rules.sh` | 107개 통과 | 실제 production `Rules.kt`를 Kotlin CLI로 컴파일·실행. PIN/아이디/비밀번호/이메일/카드 Luhn/유효기간/CVC/호스트/상태 표시 검증. |
| `tools/audit.py` | 59개 통과 | 28개 화면 라우트, 제거 대상 코드, 호스트, PG/Pay 분리, 멱등성 키 위치, XML, 민감정보 로그·가상 응답 부재를 정적 검사. |
| `tools/verify-syntax.sh` | 26개 Kotlin/Kotlin DSL 파일, 구문 오류 0 | Kotlin PSI 파서 검사. Android 타입 검사나 Compose 컴파일이 아님. |

아래 검사 명령을 프로젝트에 포함했다.

```sh
sh tools/verify-rules.sh
python3 tools/audit.py
KOTLIN_HOME=/path/to/kotlin sh tools/verify-syntax.sh
```

### 초기 전달본에서 작성했지만 실행하지 못한 테스트

`app/src/test/.../PayContractTest.kt` 8개, `HttpTest.kt` 8개 JUnit 테스트를 포함했다.
요청 본문 필드, 카드 원문 분리, PG Bearer 제외, Pay 키/본문 유지, 카드 선택 PATCH와 승인 POST 분리,
기기 헤더, 204, 오류 표시, 리다이렉트, 취소를 검사한다.
HTTP 테스트는 실제 `Http`와 JSON 빌더를 MockWebServer에 연결한다. 실제 PG·Pay 백엔드 통합 테스트가 아니다.
카드 등록 상태 머신 전체/Android Keystore/생체 OS 프롬프트/화면 클릭 E2E 테스트로 제시하지 않는다.

### 초기 전달본의 Android 빌드 시도

실행한 명령:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

결과: 종료 코드 1. 공식 Wrapper JAR 다운로드 단계에서 다음 오류로 중단됐다.

```text
Downloading official Gradle 8.11.1 Wrapper JAR...
java.net.UnknownHostException: raw.githubusercontent.com
```

당시 전달 환경에는 Android SDK도 설치되어 있지 않았다. 따라서 Gradle Sync, Android 타입/리소스 검사,
Compose 컴파일, JUnit 16개 실행, APK 생성, 에뮬레이터·실기기 동작을 검증했다고 주장하지 않는다.
공식 Wrapper JAR은 초기 ZIP에 없었다. 이번 빌드에서 고정 버전·SHA-256으로 공식 JAR을 준비했다.
인터넷 연결이 가능한 개발 PC에서 README 순서대로 준비·동기화·빌드해야 한다.

### 코드 규모

같은 기준인 `src/main/**/*.kt` 기준으로 기존 26개/2,889줄 → 19개/1,694줄.
빈 줄·주석을 포함한 물리적 줄 수이며 기능 완성률·빌드 검증률이 아니다.
런타임은 하나의 `app` 모듈이다. 초기 전달본에서 실제 요청에 필요 없는 추상화·설정 엔진·선행 알림 구현을 제거했고,
이번 변경은 기존 흐름에 FID 등록과 승인 알림 수신을 추가했다.

### 서버와 운영 범위

Notion 문서는 읽었지만 실제 배포 서버에는 접속하지 않았다. PG는 개발용 Mock API이며 실제 배포는 확인하지 않았다.
Core의 미확정 DTO, 약관 원문·증빙 부재, FCM 계약과 검증 한계, 앱 링크 서버 설정은 CONTRACT.md에 명시했다.
이 ZIP은 실행/수정 가능한 프로젝트 소스를 제공하려는 재작성본이며 운영 배포 완료나 인증·보안 심사를 받은 결과물이 아니다.
