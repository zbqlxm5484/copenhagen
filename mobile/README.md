# Copenhagen Android

Kotlin + Jetpack Compose. 단일 `app` 모듈.
코펜하겐 페이앱의 현재 구현을 모노레포 `mobile/`에서 관리한다. PG Mock 토큰화와 Pay 요청을 직접 연결한다.
가상 로그인·가상 카드·가상 승인 응답은 없다.

## 실행

1. JDK 17 또는 21, Android SDK 35를 준비하고 `mobile/`로 이동한다. `local.properties`의 `sdk.dir` 또는 `ANDROID_HOME`으로 SDK 경로를 지정한다.
2. `mobile/`에서 `./gradlew --version`을 실행한다. Windows는 `gradlew.bat --version`.
   포함된 공식 Wrapper JAR의 SHA-256을 검사하고, 최초 실행 시 Gradle 8.11.1을 내려받는다.
3. Android Studio → **Open** → 저장소의 `mobile/` 폴더 → Gradle Sync → `app`의 `debug` 실행.

```sh
chmod +x gradlew
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

빌드 조합: AGP 8.9.2 / Gradle 8.11.1 / Kotlin 2.1.20 / Compose BOM 2025.04.01 / min SDK 26.
검증 명령과 수행 결과는 [검증 기록](docs/VERIFICATION.md)에 기록한다. 실제 Firebase·백엔드·실기기 연결은 별도 검증이 필요하다.

## 서버 주소

앱 **로그인 상단 설정** 또는 **설정 → 서버 설정**에서 Core, Pay, PG 주소를 각각 저장한다.
주소 변경 시 기존 로그인과 메모리 상태를 지운다. PG에는 Core의 Bearer 토큰을 보내지 않는다.
기본값을 빌드에 넣으려면 `environment.properties.example`을 `environment.properties`로 복사한다.

```properties
CORE_BASE_URL=https://core.example.com/
PAY_BASE_URL=https://pay.example.com/
PG_MOCK_BASE_URL=http://10.0.2.2:8082/
APP_LINK_HOST=links.example.com
```

위 주소는 형식 예시다. `8082`는 실제 PG 포트라는 뜻이 아니며, 외부에 노출한 개발 PG 포트로 교체해야 한다.
주소에는 `/api/core/v1`, `/api/pay/v1`, `/api/pg/v1/payment-methods/tokenize`를 붙이지 않는다.
실기기는 개발 서버의 실기기 접근 가능 주소를 사용한다. `http://pg-mock:8080`은 Docker 내부 주소라 앱에서 사용할 수 없다.
빌드 속성 `-PCORE_BASE_URL=...` 등은 파일 기본값보다 우선하며, 앱에 저장한 주소는 빌드 기본값보다 우선한다.

## 수정할 파일

| 수정 대상 | 위치 (`app/src/main/kotlin/com/copenhagen/wallet/` 기준) |
|---|---|
| PG/PIN/생체 승인 요청 본문 | `data/PayContract.kt` |
| API 경로·인증 갱신·PG 호출 | `data/Api.kt` |
| HTTP 전송·응답 오류 처리 | `data/Http.kt` |
| 응답 필드 | `data/Models.kt` |
| 카드 등록·별도 멱등성 키 | `features/Cards.kt` |
| 승인 조회·카드 선택·승인·거절 | `features/Approvals.kt` |
| 배송지·주문·프로필 | `features/Account.kt` |
| 기기 생체 키·서명 | `features/Biometrics.kt` |
| 화면 배치·버튼 | `ui/*Screens.kt` |
| 공통 색·형태·타이포그래피 | `ui/Components.kt` |
| 로그인·화면 이동·탭 | `AppModel.kt`, `ui/App.kt` |
| 서버 주소·암호화 토큰 보관 | `data/Store.kt` |

Repository 인터페이스/구현체, UseCase 계층, DI 컨테이너, 범용 JSON 매핑, 이벤트 버스, 별도 `logic` 모듈, 미구현 PG/생체 어댑터를 제거했다.
FCM은 승인 알림 수신과 FID 등록만 담당한다. WorkManager와 별도 알림 계층은 없다.
데이터는 명시적인 DTO 하나로 읽고 화면에서 사용한다. 통신 실패를 빈 목록이나 성공으로 바꾸지 않는다.

## 연결 범위

- 카드 원문 → PG Mock 토큰화 → 받은 `tokenizationResult`로 Pay 등록. 두 작업은 다른 멱등성 키를 사용한다.
  PG 성공 후 Pay만 재시도하면 같은 토큰·본문·Pay 키를 재사용한다. 원문과 토큰은 디스크에 저장하지 않는다.
- 카드 목록·기본 카드·삭제, PIN 상태·등록·변경, 생체 키 등록, 승인 목록·상세·카드 선택 PATCH·승인·거절을 연결했다.
- `APPROVED`는 승인 전달, `COMPLETED`는 결제 완료로 구분한다. 응답 유실 시 상태를 먼저 조회한다.
- 등록 카드가 필요한 승인에서는 카드 등록을 마친 뒤 같은 승인에 카드 선택 PATCH를 실행한다.
- 배송지 검색은 Kakao 우편번호 embed API를 사용한다. 배송지 수동 입력도 가능하다.
- 기존 27개 화면 ID와 서버 설정 화면을 유지했다. 변경된 계약·제약과 화면/이벤트 위치는 `docs/CONTRACT.md`, `docs/SCREENS.md`에 있다.

**제공된 PG는 운영 PG가 아니다.** Notion에는 개발·로컬 전용, 운영 사용 금지, API 구현 예정으로 기재되어 있다.
따라서 토큰화는 `debug`에서만 가능하며 `release`는 차단한다. 실카드번호·실CVC를 입력하면 안 된다.
서버가 실제로 배포되어 도달 가능한지는 별도로 확인해야 한다.

Core 배송지·프로필·주문의 상세 JSON 필드는 아직 미확정이다. 현재 선택한 필드명과 수정 위치를 `docs/CONTRACT.md`에 명시했다.
약관 원문·동의 증빙 계약은 제공되지 않았다. 약관 UI의 선택은 서버에 기록된 법적 동의 증빙이 아니다.
FCM 승인 알림 설정과 서버 계약은 아래를 따른다.

## Firebase / 승인 알림

1. 서버가 푸시를 발송할 Firebase 프로젝트에 Android 앱 `com.copenhagen.wallet`을 등록한다.
2. 해당 앱의 `google-services.json`을 내려받아 `app/google-services.json`에 넣고 다시 빌드한다.
   Firebase 설정 파일은 Git에 포함하지 않는다. 파일 없이도 개발 빌드는 가능하지만 알림 연결은 실패로 표시된다.
3. 로그인 후 Android 알림 권한을 허용한다. 설정 → 알림 설정에서 다시 변경할 수 있다.

로그인 성공 및 저장된 로그인 복원 시 FCM `register()` 후 Firebase Installation ID(FID)를 얻어
`PUT {PAY_BASE_URL}/api/pay/v1/notification-installations`에 Access Token과 `{"fid":"..."}`를 보낸다.
기존 로그인 `deviceId`는 그대로 유지한다. FID나 FCM 토큰으로 대체하지 않는다.
등록 실패는 로그인을 취소하지 않고 설정 화면에 표시하며, **알림 다시 연결**로 현재 FID를 재등록할 수 있다.
FID 동기화는 로그인·로그인 복원·명시적 재연결 시 수행하며 별도 백그라운드 등록 작업은 없다.
[Firebase 공식 FID 설정](https://firebase.google.com/docs/cloud-messaging/android/get-started)에 따라
`firebase_messaging_installation_id_enabled=true`와 `register()`를 사용한다.

서버 메시지의 data 필드는 다음 형태를 사용한다. `approvalId`는 실제 승인 UUID여야 한다.

```json
{
  "data": {
    "approvalId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

위는 payload 일부다. 실제 발송에는 등록한 FID 대상 지정이 필요하다. data-only 메시지는 Android 우선순위를 HIGH로 발송한다.
앱이 직접 받은 메시지는 승인 알림으로 표시한다. `notification + data` 메시지가 백그라운드에서 도착하면
Firebase가 표시한 알림을 누를 때 Activity의 `approvalId`를 처리한다. 별도 `click_action`은 지정하지 않는다.
알림을 누르면 기존 로그인/PIN 확인 → 승인 상세 GET → 카드 선택·PIN/생체 승인 흐름을 사용한다.
푸시의 금액·상점 정보로 결제를 진행하거나 자동 승인하지 않는다. 푸시 진입은 `APP_LINK_HOST` 설정과 무관하다.

등록 API는 [제공된 Notion 문서](https://app.notion.com/p/FCM-FID-39285e9160e6838a9f9901eeaa0f474d)의
계약을 구현했다. 문서 상태는 조회 시점에 `시작 전`이었으며 실제 서버 배포·Firebase 발송은 확인하지 않았다.
Firebase 설정 파일과 실제 기기를 준비한 뒤 로그인 등록, 전경/배경 알림, 종료 상태에서 알림 열기를 검증해야 한다.

## 관련 문서

- [스토리 S15P21A207-456](https://ssafy.atlassian.net/browse/S15P21A207-456)
- [화면 구성](docs/SCREENS.md)
- [연동 계약과 확인할 사항](docs/CONTRACT.md)
- [미사용 API와 기능 확장 후보](docs/UNUSED_APIS.md)

`environment.properties`, `local.properties`, `app/google-services.json`, 서명 키와 빌드 산출물은 Git에 포함하지 않는다. 서버 주소는 앱의 서버 설정 화면에서도 입력할 수 있다.
