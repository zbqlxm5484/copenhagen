# 화면과 동작 위치

화면 코드는 `app/src/main/kotlin/com/copenhagen/wallet/ui/`, 상태/요청 순서는 `AppModel.kt`와 `features/`에 있다.
원본 27개 화면과 서버 설정 X01을 유지했다. 표는 소스 위치 대응표이며 실제 서버 E2E 통과를 뜻하지 않는다.

## 화면

| ID | UI 파일 |
|---|---|
| A01 | AuthScreens.kt |
| A02 | AuthScreens.kt |
| A03 | AuthScreens.kt |
| A04 | AuthScreens.kt |
| A08 | AuthScreens.kt |
| A05 | AuthScreens.kt |
| A06 | AuthScreens.kt |
| A07 | AuthScreens.kt |
| W01 | CardScreens.kt |
| W02 | CardScreens.kt |
| C01 | CardScreens.kt |
| C02 | CardScreens.kt |
| C03 | CardScreens.kt |
| D01 | AddressScreens.kt |
| D02 | AddressScreens.kt |
| D03 | AddressScreens.kt |
| P01 | ApprovalScreens.kt |
| P02 | ApprovalScreens.kt |
| P03 | ApprovalScreens.kt |
| P04 | ApprovalScreens.kt |
| P05 | ApprovalScreens.kt |
| P06 | AuthScreens.kt |
| P07 | ApprovalScreens.kt |
| H01 | HistorySettingsScreens.kt |
| H02 | HistorySettingsScreens.kt |
| S01 | HistorySettingsScreens.kt |
| S02 | AuthScreens.kt |
| S03 | ConnectionScreen.kt |
| T01 | CartScreen.kt |
| G01 | ProductScreens.kt → ProductSearchScreen |
| G02 | ProductScreens.kt → ProductDetailScreen |
| X01 | HistorySettingsScreens.kt → ServerScreen |

## 원본 동작과 달라진 점

- A04: 최신 Pay 문서의 NOT_REGISTERED는 A05로 바로 연결한다.
- A08/E06: 계약을 가정한 시연 버튼을 제거했다. REVOKED일 때 Core 로그인으로 계정 재확인을 수행한다.
- A06/E08: 제공된 Pay v1.7에는 proof가 없으므로 PIN만 등록한다.
- C02/E19: 약관 원문은 미제공. 화면 선택은 실제 약관 증빙이 아니다.
- D01~D03: 실제 Kakao 주소 검색. Core 전송 필드의 미확정 부분은 CONTRACT.md에 명시했다.
- P02/E32: 카드 선택이 Pay의 별도 PATCH로 바뀌었다. 기본 카드 지정과 다르다.
- P04: 서버 challenge의 UTF-8 원문을 기기 키로 서명한다.
- S01: FID 등록 상태와 실패를 표시한다. 알림 재연결 및 Android 알림 설정 열기를 제공한다. 로그아웃 시 FID 해제 후 세션을 정리하며 서버 정리 실패는 A01에서 안내한다. X01 서버 변경도 이전 서버 연결을 먼저 정리한다.
- S03: 설정에서 연결된 AI 서비스의 로고·이름을 조회한다. 행을 펼쳐 권한·연결일·마지막 사용일을 확인하고 연결 해제 아이콘으로 확인 대화상자를 연다. 해제 후 재조회하며 실패·빈 목록·로고 부재를 구분한다.
- T01: 지갑의 장바구니 버튼으로 진입한다. 상품 이미지·옵션 ID·단가×수량과 서버 합계를 표시한다. 아이콘으로 수량 증감·삭제·새로고침을 수행하고 변경 후 재조회한다. 예상 금액의 정보 아이콘에서 할인·배송비 미확정 상태를 확인한다. 버전 충돌·응답 유실은 짧은 상태로 표시하며 GET 성공 전 불확실한 버전의 편집을 막는다.

## 이벤트

- G01: 지갑 → 상품 탐색. 검색 입력창·필터 아이콘, 판매처·가격·정렬 조건, 10개 단위 페이지 이동과 offers 비교를 제공한다. 판매처 행을 누르면 G02로 이동한다. 조건 변경 시 이전 결과를 비우고 일부 판매처 실패·빈 결과·재시도를 구분한다. 미지원 브랜드·카테고리 필터는 노출하지 않는다.
- G02: 상품 이미지·가격·판매 상태, 옵션 선택 칩과 재고·배송 아이콘을 표시한다. 옵션 선택 시 재조회하고 부가 상품 정보는 펼쳐서 확인한다. 뒤로 가면 검색 상태를 복원한다. 상시 안내문과 담기/구매 버튼은 없다.

| 원본 ID | 동작 | 현재 위치 |
|---|---|---|
| E01 | 다음 | AuthScreen → show(A02) |
| E02 | 회원가입 | AuthScreen → show(A03) |
| E03 | 로그인 | AppModel.login |
| E04 | 가입하기 | Signup → Api.signup |
| E05 | 상태 확인 / 재시도 | AppModel.checkPin |
| E06 | 계정 재확인 | AuthScreen(A08) → AppModel.login(reauthenticate=true) |
| E07 | PIN 입력 완료 | AppModel.setFirstPin |
| E08 | PIN 등록 | AppModel.confirmPin |
| E09 | 생체 인증 사용 | AppModel.enroll → Biometrics.enroll |
| E10 | 나중에 하기 | AuthScreen → AppModel.enterHome |
| E11 | 카드 관리 | WalletScreen → open(W02) |
| E12 | 새 카드 등록 | WalletScreen → show(C01) |
| E13 | 요청 확인하기 | AppModel.openApproval |
| E14 | 모든 승인 요청 | WalletScreen → open(P01) |
| E15 | 기본 카드 지정 | Cards.makeDefault |
| E16 | 카드 삭제 | Cards.delete |
| E17 | 카드 추가 | CardManagerScreen → show(C01) |
| E18 | 다음 | Cards.prepare |
| E19 | 약관 상세 | ConsentScreen |
| E20 | 동의하고 등록 | Cards.register |
| E21 | 지갑 / 결제 요청으로 | AppModel.finishCard |
| E22 | 배송지 추가 | Account.newAddress |
| E23 | 배송지 수정 | Account.editAddress |
| E24 | 기본 배송지 지정 | Account.defaultAddress |
| E25 | 주소 검색 | AddressEditorScreen → show(D03) |
| E26 | 주소 선택 | PostalSearchScreen → Account.form |
| E27 | 배송지 저장 | Account.saveAddress |
| E28 | 배송지 삭제 | Account.deleteAddress |
| E29 | 승인 요청 열기 | AppModel.openApproval |
| E30 | 승인하기 | AppModel.beginApproval |
| E31 | 요청 거절 | Approvals.reject |
| E32 | 결제 카드 변경 | Approvals.selectCard |
| E33 | 먼저 카드 등록 | ApprovalDetailScreen → show(C01) |
| E34 | PIN 확인·승인 | AppModel.approvePin |
| E35 | 생체 인증 성공 | AppModel.approveBiometric |
| E36 | PIN으로 확인 | ApprovalAuthScreen → show(P03) |
| E37 | 닫기 / 지갑으로 | AppModel.closeApproval |
| E38 | 기록 보기 | AppModel.closeApproval(toHistory=true) |
| E39 | 다시 상태 확인 | AppModel.checkPin |
| E40 | 서버 상태 확인 | Approvals.reconcile |
| E41 | 동일 요청 재시도 | Approvals.reconcile → Approvals.retry |
| E42 | 승인 상태 필터 | HistoryScreen → Approvals.loadHistory |
| E43 | 주문 기록 탭 | HistoryScreen → Account.loadOrders |
| E44 | 주문 상세 | Account.openOrder |
| E45 | PIN 변경 | SettingsScreen → show(S02) |
| E46 | 생체 인증 등록 | SettingsScreen → AppModel.enroll |
| E47 | 로그아웃 | AppModel.logout |
| E48 | PIN 변경 저장 | AppModel.changePin |
| N01 | 카드 | AppModel.selectTab(W01) |
| N02 | 배송지 | AppModel.selectTab(D01) |
| N03 | 기록 | AppModel.selectTab(H01) |
| N04 | 설정 | AppModel.selectTab(S01) |
