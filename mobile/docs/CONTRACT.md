# 계약과 실제 연결 경계

기준: [사용자가 지정한 Pay API v1.7 및 PG Mock 레퍼런스](https://app.notion.com/p/Pay-api-30285e9160e683dd942181966ae50da3#30285e9160e683dd942181966ae50da3), 2026-09-17 조회.
문서의 배포 상태와 앱을 실제 서버에 연결해 검증했다는 뜻은 다르다. 전달 환경에서는 서버를 호출하지 않았다.

## PG

`POST {PG_MOCK_BASE_URL}/api/pg/v1/payment-methods/tokenize`

- 요청: `cardNumber`, `expiryMonth`, `expiryYear`, `cvc` 네 필드만 전송한다.
- 카드번호 공백·하이픈 제거, 13~19자리 Luhn, 유효 월/연도, CVC 3~4자리 검증.
- Bearer 없음. `Idempotency-Key: tokenize-{uuid}`.
- 응답의 `provider`, `providerTokenRef`, `brand`, `last4`, `expiryMonth`, `expiryYear`를 `TokenizedCard`로 읽는다.
- `provider`를 포함한 토큰화 결과는 실제 PG 응답값 그대로 Pay에 전달한다.
- Pay 등록은 `POST /api/pay/v1/payment-methods/cards`, Bearer와 별도 `register-card-{uuid}` 키, 본문은 `{ "tokenizationResult": <PG 응답> }`.
- 토큰화 실패 시 Pay를 호출하지 않는다. Pay 등록만 실패한 경우 이미 받은 토큰과 Pay 키를 유지한다.
- 명시적으로 흐름을 취소하거나 백그라운드로 나가면 입력·토큰·재시도 본문을 메모리에서 해제한다. 프로세스 종료 후 재시도 키를 복구하는 디스크 저널은 없다.
- Pay 응답을 받지 못한 상태에서 뒤로 가면 카드 목록을 확인한다. 새 작업을 시작하기 전에 등록 결과를 확인해야 한다.
- UI, 오류 문구, 로그, 저장소에 PAN·CVC·PG 토큰을 출력/저장하지 않는다. 앱의 네트워크 본문 로그 기능은 없다.
- 앱은 `/internal/v1/charges`, `/merchant/v1/payments`를 호출하지 않는다.

Notion은 PG Mock API를 **구현 예정**, **운영 사용 금지**로 표시하며 앱용 외부 포트도 미설정이라고 설명한다.
이 프로젝트는 그 공개 계약의 클라이언트이지 PG 서버 구현물이나 운영 PG SDK가 아니다.

## Pay v1.7로 바뀐 부분

| 항목 | 적용 |
|---|---|
| 최초 PIN | `POST /users/me/payment-pin`에 `pin`만 보낸다. 최신 제공 문서에 없는 `reauthProof` 어댑터를 제거했다. |
| PIN 변경 | `currentPin`, `newPin`. 성공은 204이며 GET으로 등록/잠금을 판정한다. |
| REVOKED | A08에서 Core 로그인으로 계정을 다시 확인하고 PIN 화면으로 이동한다. 재등록 허용 여부는 Pay 응답이 결정한다. PIN 폐기를 클라이언트에서 해제하지 않는다. |
| 생체 키 | Android Keystore의 인증 제한 EC P-256 / SHA256withECDSA. 공개키는 X.509 SPKI DER → Base64URL 무패딩. |
| 생체 서명 | challenge를 디코드하지 않고 **문자열 UTF-8 원문**에 서명한다. DER 서명을 Base64URL 무패딩으로 전달한다. |
| 기기 선택 | 승인 상세 GET에 등록 때와 동일한 `X-Device-Id`를 보낸다. |
| 카드 선택 | 승인 전 `PATCH /payment-approvals/{id}/payment-method`에 `paymentMethodId`. |
| 승인 | 본문에 `authentication`만 넣는다. PIN은 `method/pin`, 생체는 `method/challengeId/assertion`. |
| 승인 결과 | `APPROVED`와 `COMPLETED`를 구분한다. Mandate·Credential은 앱이 사용하지 않으므로 DTO에 저장하지 않는다. |
| 실시간 | FCM `data.approvalId`로 승인 알림을 표시한다. 알림을 누르면 인증 후 상세를 조회한다. 목록 새로고침도 유지한다. |

PIN·카드 등록·변경·삭제·기본 지정·생체 등록·승인·거절·선택에 멱등성 키를 보낸다.
승인 응답이 유실되면 현재 흐름이 살아 있는 동안 동일 본문·동일 키로만 명시적 재시도가 가능하다.
서버가 확정적으로 거부한 4xx는 미확정 성공으로 취급하지 않는다.
호스트 변경/로그아웃 시 토큰과 사용자별 화면 상태 및 로컬 생체 키를 비운다. 기기에서 생체 인증을 끄는 동작은 로컬 키 삭제이며 서버 자격증명 해지 API가 아니다.

## Core: 미확정 JSON 필드

첨부된 `api-contracts.json`의 경로·인증 계약을 사용했다. 최신 [배송지 등록 문서](https://app.notion.com/p/ea885e9160e6838398518100290cd835)도 개념적 필드명과 `addressId` 반환만 기술한다.
상세 응답 필드가 정의되지 않은 부분은 실제 서버 계약이라고 주장하지 않는다. 아래는 **이 클라이언트가 현재 기대하는 명시적 구조**다.
범용 JSON 경로 설정이나 여러 형식의 자동 추측 대신 `data/Models.kt`, `features/Account.kt`만 고친다.

| API | 클라이언트 기대 구조 |
|---|---|
| GET `/api/core/v1/users/me` | `{name, loginId, email}` |
| GET `/addresses` | `{items:[{addressId,recipient,phone,postalCode,address,detail,memo,isDefault}]}` |
| GET `/addresses/{id}` | 위 주소 객체 단독 |
| POST `/addresses` | 요청 `{recipient,phone,postalCode,address,detail,memo}`, 응답 `{addressId}` |
| PUT `/addresses/{id}` | POST와 같은 요청, 응답 본문 미사용 |
| PATCH `/addresses/{id}/default`, DELETE `/addresses/{id}` | 응답 본문 미사용, 이후 목록 재조회 |
| GET `/orders` | `{items:[{orderId,title,status,amountMinor,currency,createdAt,...}],nextCursor?}` |
| GET `/orders/{id}` | 위 주문 객체에 `items:[{name,option,quantity}]`, `address`, `paymentLabel` 선택 필드 |

주문 조회는 `size=20`, `cursor`, `status`를 사용한다. 승인 검색어는 로컬 필터이며 정의되지 않은 검색 파라미터를 보내지 않는다.
Core 응답이 기대 구조와 다르면 오류로 표시하며 가짜 프로필/배송지/주문으로 채우지 않는다.

## Core: AI 서비스 연결

저장소 `AgentConnectionController`와 `AgentConnectionResponse`를 기준으로 확인했다.
`GET /api/core/v1/users/me/agent-connections`는 래퍼 없는 배열이며 각 항목은
`connectionId`, `clientName`, `logoUrl?`, `scopes[]`, `connectedAt`, `lastUsedAt?`다.
`DELETE /api/core/v1/users/me/agent-connections/{connectionId}`는 UUID와 사용자 Bearer를 사용하며 204 빈 본문을 허용한다.
해제 성공 후 목록을 재조회한다. 해제 실패는 기존 목록을 유지하고, 해제 후 재조회 실패는 이전 목록을 숨겨 재시도를 제공한다.
세션 종료 시 목록을 비운다. 로고 로딩·캐시·취소는 Coil Compose 2.7.0을 사용하며, 이미지 요청에 사용자 Bearer를 전달하지 않는다.
로고가 없거나 로딩에 실패하면 대체 아이콘을 표시한다. 실제 서버 연결은 미검증이다.

## Core: 장바구니 조회·편집

저장소 `CartController`·`CartView`에 맞춰 `GET /api/core/v1/cart`를 연결했다.
응답은 `{cartId?, version, items[], summary}`이며 아직 장바구니가 없는 사용자는 `cartId=null`, `version=0`, `items=[]`다.
상품은 `cartItemId`, `merchantId`, `productId`, `optionValueIds[]`, `productName`, `productUrl?`, `imageUrl?`,
`quantity`, `unitPrice:{amount,currency}`, `lineTotal`을 사용한다. 옵션 이름이 없으므로 옵션 ID로 표시한다.
합계는 서버의 `itemsTotal`, `discountTotal`, `shippingFee`, `estimatedTotal`, `currency`를 그대로 표시하며 금액은 Long으로 보존한다.
현재 서버의 할인·배송비는 0 고정이다. 합계를 예상 금액으로 표시하며 정보 아이콘을 누르면 할인·배송비 미확정 상태를 확인할 수 있다. 결제 확정이나 무료 배송으로 단정하지 않는다.
조회 실패와 정상 빈 장바구니를 구분하며 세션 종료 시 데이터를 비운다. AI 내부 장바구니와의 공유 및 Checkout 연결은 미검증이다.

수량 변경은 `PATCH /cart/items/{cartItemId}`에 `{cartVersion,quantity}`를 보내며 수량은 1 이상이다.
삭제는 확인 후 `DELETE /cart/items/{cartItemId}?cartVersion=...`로 호출한다. 두 응답의 `{cartVersion}`을 반영하고 GET으로 상품·합계를 재조회한다.
DELETE는 200 응답 본문이 필요하며 204를 버전 응답으로 취급하지 않는다.
모든 편집은 기존 `AppModel.execute`로 직렬화하고 진행 중 입력을 막는다. 상품 수량·금액을 낙관적으로 변경하지 않는다.
`CART_VERSION_CONFLICT`·`CART_ITEM_NOT_FOUND`는 최신 장바구니를 조회하고 변경 안내를 표시한다. 쓰기 요청을 자동 반복하지 않는다.
네트워크 실패·5xx·응답 파싱 실패로 결과가 불명확하면 이전 상태를 숨기고, 사용자가 GET을 성공시킨 후에만 다시 편집할 수 있다.
확정적인 일반 4xx 거절은 기존 내용을 유지한다. 취소 및 성공 후 GET 실패에도 불확실한 버전으로 편집하지 않는다.

## Core: 상품 검색

저장소 `ProductController`·`ProductService` 및 공개 DTO를 기준으로 `POST /api/core/v1/products/search`를 연결했다.
본문은 `query`, `category`, `brand`, `merchantId`, 선택적인 `minPrice`/`maxPrice`, `page`(1부터), `pageSize`(앱은 10), `sort`다.
정렬은 `RELEVANCE`, `PRICE_ASC`, `PRICE_DESC`이며 가격은 0 이상의 Long 및 최솟값 ≤ 최댓값을 검증한다.
검색어·판매처·가격·정렬을 변경하면 페이지와 결과를 초기화한다. 페이지 이동은 기존 결과를 교체하며 늦은 이전 응답은 폐기한다.
응답은 `{publicUserRef, connectionRef, data}`이며 `data.products[].offers[]`의 판매처·nullable 가격·판매 상태를 표시한다.
`total`, `hasNext`, `checkedAt`, `failedMerchants`로 페이지와 일부 판매처 실패를 구분한다. 같은 productId라도 판매처가 다를 수 있어 ID만으로 합치거나 제거하지 않는다.
판매처 선택지는 서버의 `merchants[]`에서 얻는다. 누락 가격을 0으로 표시하지 않고 알 수 없는 판매 상태는 확인 필요로 표시한다.
현재 공개 서버는 category 또는 brand가 비어 있지 않으면 모든 상품을 제외한다. 두 값은 빈 문자열로 보내고 해당 필터는 UI에 노출하지 않는다.
서버가 해당 메타데이터/필터를 지원하면 후속으로 노출할 수 있다. 내부 MCP 검색 계약을 공개 API와 혼용하지 않는다.

## Core: 상품 상세

`GET /api/core/v1/products/{productId}`는 선택한 offer의 `merchantId`를 필수 쿼리로 보내고, 옵션 선택 시에만 `optionId`를 보낸다.
동적 productId에 경로 구분자(`/`, `\`) 또는 상위 경로(`.`, `..`)를 허용하지 않는다. 나머지 경로 문자와 쿼리는 기존 Http/OkHttp가 인코딩한다.
응답은 외부 envelope의 `data`이며 `status=FOUND`의 `product`와 `status=NOT_FOUND, product=null`을 구분한다.
ProductDetail의 `offer`, `variants`, `selectionStatus`, `selectedOptionId`, `optionsComplete`, `optionsRequired?`, `stockQuantity?`, `shipping`을 읽는다.
옵션 선택·재시도 시 상세를 다시 읽는다. 이전 선택의 늦은 응답을 폐기하며 상세에서 돌아와도 검색 조건·페이지·결과는 유지한다.
다른 판매처는 검색 결과로 돌아가 선택한다. 새 상세 진입에는 이전 판매처의 optionId를 전달하지 않는다.
nullable 가격·재고·배송비·구매 허용을 0/무료/구매 가능으로 바꾸지 않는다. `purchaseAllowed`는 아이콘과 접근성 설명으로 표시한다.
현재 공개 서버는 stockQuantity=null, shipping=UNKNOWN, purchaseAllowed=null을 반환하며 상세 brand/category/specs도 제공하지 않는다.
상품 설명·사양 등 부가 정보는 펼쳐서 확인한다. 장바구니 추가·구매 시작 버튼과 optionId→optionValueIds 변환은 구현하지 않는다.

## 약관·배송지 검색·앱 링크

원본의 5개 필수 약관 선택 화면은 유지했다. 원문은 `app/src/main/assets/terms/1.txt`~`5.txt`에서 읽는다.
실제 원문은 제공되지 않아 빈 예시 법률 문서를 만들어 넣지 않았다. `debug`에서는 원래 체크 UI를 지나 PG Mock을 연결할 수 있지만, 이는 법적 동의 증빙을 수집한 것이 아니다.
동의 기록 API도 정의되지 않아 임의의 `/consents` 요청을 추가하지 않았다. 운영 카드 등록은 PG Mock 제약으로 차단된다.

배송지 검색은 [Kakao 공식 우편번호 가이드](https://postcode.map.kakao.com/guide)의 embed 방식이다.
네이티브 JavaScript 인터페이스 없이 선택된 우편번호·주소만 내부 URL로 반환받는다. WebView에 사용자 토큰을 넣지 않는다.
서드파티 검색의 실제 기기/WebView 동작은 별도 검증이 필요하다. 원본에 특정 검색 공급자 계약이 없어서 현재 사용 가능한 공개 API 하나를 직접 연결했다.

`APP_LINK_HOST`에 `/approvals/{UUID}` HTTPS 링크를 설정한다. 해당 도메인의 `assetlinks.json`은 실제 서명 인증서로 서버에서 배포해야 한다.
앱은 링크에서 받은 상점·금액을 믿지 않고 사용자 인증 후 승인 ID로 서버를 조회한다. 임의 외부 복귀 URL을 실행하지 않는다.

## FCM FID 등록과 승인 알림

[FID 등록·갱신 문서](https://app.notion.com/p/FCM-FID-39285e9160e6838a9f9901eeaa0f474d)를 따른다.
로그인 및 저장된 로그인 복원 시 `PUT /api/pay/v1/notification-installations`, 사용자 Access Token,
본문 `{"fid":"firebase-installation-id"}`로 등록한다. 응답 본문 필드에 의존하지 않는다.
Firebase FID 모드를 활성화하고 FCM `register()` 완료 후 Installations의 현재 ID를 사용한다.
기존 Core 로그인과 생체 인증의 `deviceId`는 변경하지 않는다.
등록 실패는 설정 화면에 표시하며 로그인은 계속할 수 있다. 설정에서 재등록할 수 있다.
FID가 이후 변경되면 다음 로그인·로그인 복원·재연결 시 동기화한다. 별도 백그라운드 갱신 작업은 없다.

메시지에서 `data.approvalId` UUID만 사용한다. 전경 및 data-only 배경 수신은 앱이 알림을 만들고,
notification+data 배경 수신은 Firebase가 알림을 표시한 뒤 launcher Intent의 extra를 앱에 전달한다.
알림 클릭은 기존 승인 진입 로직을 사용하며 금액·상점·승인 가능 여부는 Pay에서 다시 조회한다.
서버 payload는 `approvalId`를 포함하고 별도 `click_action`을 지정하지 않아야 한다.
알림 권한이 거부되면 알림이 표시되지 않는다. 승인 목록 조회는 계속 사용할 수 있다.

등록 API 문서의 상태는 조회 시점에 `시작 전`이다. 실제 배포 여부와 FID 대상 발송은 확인하지 않았다.
v5 및 저장소 NotificationInstallationController의 계약으로 `DELETE /api/pay/v1/notification-installations/{fid}`를 연결했다.
로그아웃·로그인 상태에서의 계정 전환·서버 주소 변경은 이전 계정/서버의 인증으로 FID 해제 → Core 로그아웃 → 로컬 세션 정리 순서다.
204 빈 본문을 처리하며, 해제와 Core 로그아웃은 각각 최대 10초 시도한다. 해제 실패 후에도 Core 로그아웃을 시도한다.
실패·타임아웃·취소에도 로컬 세션을 정리하고, 원격 작업 실패는 로그인 화면에 안내한다. 취소 후 새 요청이나 백그라운드 재시도를 만들지 않는다.
계정 전환 중 원격 정리를 확인하지 못하면 로컬 로그아웃 후 안내를 먼저 표시하며, 사용자가 새 로그인을 다시 시작할 수 있다.
FID는 요청 경로에 그대로 전달하지만 HTTP 진단 로그의 해당 경로에서는 마스킹한다.
실제 Firebase/Pay 연결 해제·계정 전환은 개발 서버와 기기에서 추가 확인해야 한다.
