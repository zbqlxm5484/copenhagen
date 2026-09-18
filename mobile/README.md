# Copenhagen Android

MCP에서 시작한 구매를 앱에서 이어받고 최종 결제를 승인하는 Compose 앱이다.
상품 탐색과 비교는 MCP UI가 제공한다.

## 제공 화면

- 기존 Core 계정 로그인·토큰 갱신·로그아웃
- 배송지 등록·기본 배송지 지정·삭제와 기존 구매 요청 재개
- 결제수단 조회·기본 카드 지정·삭제·승인 카드 선택
- 개발용 PG Mock 카드 토큰화 및 Pay 카드 등록 (debug 빌드 전용)
- 결제 PIN 등록·변경, PIN 승인, 기기 생체 인증 등록·challenge 서명 승인
- 구매·승인 목록, 최종 상품·수량·금액 확인, 승인·거절·결제 결과 확인
- FCM 수신, 알림을 누른 뒤 구매/승인 화면 이동, HTTPS App Link

프로덕션 카드 발급사의 SDK·호스팅 토큰화 화면은 아직 정해지지 않았다.
따라서 release 빌드는 테스트 카드 등록을 제공하지 않으며 이미 등록된 카드의 선택·승인을 지원한다.
실제 카드 정보로 PG Mock을 호출하지 않는다.

## 환경 설정

Gradle project property 또는 같은 이름의 환경 변수를 사용한다.
실제 값은 로컬 사용자 Gradle 설정이나 CI 비밀정보에 둔다.

| 설정 | 용도 |
|---|---|
| API_BASE_URL | Core·Pay를 라우팅하는 HTTPS gateway origin |
| PG_MOCK_BASE_URL | debug 테스트 카드 등록용 gateway origin |
| APP_LINK_HOST | 구매 App Link 호스트 |
| APP_LINK_PATH_PREFIX | 구매 링크 경로 접두사. 예: /app/purchases/ |
| FIREBASE_APP_ID | Firebase Android 앱 ID |
| FIREBASE_API_KEY | Firebase Android 공개 클라이언트 설정 |
| FIREBASE_PROJECT_ID | Pay와 동일한 Firebase 프로젝트 |
| FIREBASE_SENDER_ID | Firebase messaging sender ID |

Firebase 서비스 계정 JSON과 서버 API 비밀키를 앱에 넣지 않는다.
Firebase 설정이 없으면 앱은 푸시 미설정을 표시하고 구매·승인 목록으로 계속 진행할 수 있다.
Android 13 이상은 알림 권한이 필요하다.
HTTPS 호스트에는 실제 앱 서명 인증서로 작성한 assetlinks.json을 배포한다.

토큰은 Android Keystore AES-GCM으로 암호화하며 백업/기기 이전 대상에서 제외한다.
PIN·카드번호·CVC·결제 credential·응답 본문은 로그와 화면 상태 저장소에 기록하지 않는다.
화면 캡처를 차단하며, 푸시 내용에는 구매/승인 ID만 담고 상세 정보는 로그인 후 조회한다.

## 빌드·검증

Java 21과 Android SDK 37을 준비한 뒤 실행한다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

실기기에서는 로그인 → 기기 등록 → MCP 구매 → 알림 터치 → 금액 확인 → PIN/생체 승인 →
MCP 결과 확인을 검증한다. 배송지 없음, 카드 없음, 다른 계정 로그인, 알림 거부, 앱 종료 후
알림 진입, 거절, 만료, 네트워크 응답 유실도 확인한다.
상세 API·배포 계약은 [MCP 구매와 모바일 결제 승인](../docs/api/mcp-mobile-purchase-flow.md)을 따른다.
