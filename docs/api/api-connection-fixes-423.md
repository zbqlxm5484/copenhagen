# API 연결 수정 내역 — S15P21A207-423

## 우선 수정 10건

| 번호 | 연결 문제 | 수정 |
|---|---|---|
| F1 | 실제 상품·SKU ID의 콜론이 MCP 입력/출력 및 내부 경로 검사에서 거부됨 | 실제 ID 형식 허용, 경로 이동·인코딩 우회 문자는 계속 거부 |
| F2 | 다른 판매처의 같은 상품·SKU가 장바구니 한 줄로 합쳐짐 | 조회·옵션 변경·DB 유일성을 판매처까지 포함 |
| F3 | 옵션 추가금이 목록·상세·장바구니 가격에서 유실됨 | Merchant 옵션별 가격·재고를 공유 계약으로 전달하고 Core가 선택 옵션 가격 사용 |
| F4 | Merchant → Pay 인증 설정이 Compose에서 전달되지 않음 | 단일 토큰 및 판매처별 토큰 JSON 환경 변수 전달 |
| F5 | Core 주문 Webhook이 주문 상태를 반영하지 않음 | 등록된 공개키로 서명 검증, 결제 완료 복구, 주문 갱신, 중복·역순 이벤트 처리 |
| F6 | 완료 응답 유실·재기동 후 COMPLETING이 멈춤 | lease 기반 재처리와 Merchant GET으로 결제·주문 복구, 중복 저장 방지 |
| F7 | Pay 승인 생성 실패를 다시 처리하지 않음 | 요청 JSON 영속화, 같은 구매·멱등키로 재시도, 응답 유실 뒤 만료된 승인도 먼저 조회 복구 |
| F8 | 거래 생성 이전 실패의 null 거래 ID 때문에 실패 저장 불가 | FAILED 거래의 null ID 허용, 결과와 checkout 상태를 같은 트랜잭션에 기록 |
| F9 | 앱 장바구니가 MCP·Pay의 품목 상한을 초과함 | 신규 100줄 상한, 기존 초과 장바구니 조회·삭제 허용, 주문 검증 시 상한 검사 |
| F10 | Pay FCM·알림 worker 설정이 Compose에 누락됨 | FCM·worker 설정 전달 및 배포 예제 추가 |

## 함께 반영한 구매 흐름

[MCP 구매 → 모바일 승인 계약](mcp-mobile-purchase-flow.md)에 따라 기본 배송지 기반 자동 검증,
배송지 미등록 시 같은 구매 요청 재개, 결제수단 미등록 시 앱 등록·선택, 최종 PIN/생체 승인,
거절·만료·일부 결제·알림 실패 상태 조회와 알림 재전송을 연결했다.

Android 앱의 카드 등록은 기존 PG Mock 계약을 사용하는 debug 전용이다.
프로덕션 카드 발급 연동은 제공자가 확정되어야 한다. 기존 등록 카드는 release 앱에서도 선택·승인할 수 있다.
일반 웹 frontend와 AI FDS는 이 변경에 포함하지 않는다.

## 검증 범위

최종 기준은 `develop`의 `297853638da3bc986f8f78582dfb34cd7eabb252`이며,
2026-09-18 로컬 검증에서 다음 결과를 확인했다.

| 검증 | 결과 |
|---|---|
| Backend Checkstyle·전체 build | 통과 |
| Core 테스트 (실제 PostgreSQL 포함) | 562개 통과 |
| MCP / Pay / PG Mock / Merchant Mock 테스트 | 각각 236 / 221 / 18 / 325개 통과 |
| Pay PostgreSQL 통합 테스트 | 46개 통과 |
| MCP UI 테스트 및 생성 번들 일치 | 51개 통과, 일치 |
| Android debug APK·JVM 단위 테스트·Lint | 빌드 성공, 6개 통과, Lint 오류 0개 |
| Docker Compose 구문 및 Git 공백 검사 | 통과 |

- Java Checkstyle, 전체 backend build/unit test
- 별도 테스트 PostgreSQL에서 Core API·Flyway·동시성·소유권·재개·복구 검증
- Pay postgresTest의 실제 PostgreSQL 마이그레이션·알림·결제·멱등성 검증
- Core–Pay 알림 인증·멱등 헤더 및 Merchant 완료 조회 HTTP 응답 계약
- MCP UI 상태·중복 요청·링크 검증 테스트와 생성 HTML 재빌드
- Android APK 빌드, JVM 단위 테스트, Lint
- Android HTTP 모의 서버로 401 갱신 후 동일 승인 재요청·실패 재개 멱등키·PG 인증정보 분리 검증
- Docker Compose 설정 구문 확인

실제 Firebase 계정, 배포 인증서의 App Link, 실기기 알림·생체 센서,
실제 PG 결제는 이 로컬 검증으로 확인한 범위가 아니다.
