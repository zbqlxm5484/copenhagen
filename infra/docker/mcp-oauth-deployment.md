# MCP OAuth 서버 연결 (S15P21A207-405)

Core OAuth(S15P21A207-337)와 MCP 실제 연동(S15P21A207-400)이 develop에 병합된 이후의
배포 절차다. 코드 병합과 운영 환경 설정을 구분한다. 실제 콜백·키·App Link를 등록하기
전에는 `MCP_SPRING_PROFILES_ACTIVE=core-integration`을 활성화하지 않는다.

## 주소와 역할

| 설정/주소 | 값과 역할 |
| --- | --- |
| MCP URL / Agent JWT audience | `https://j15a207.p.ssafy.io/mcp` |
| OAuth issuer | `https://j15a207.p.ssafy.io` (끝에 `/` 없음) |
| 로그인·동의 | `https://j15a207.p.ssafy.io/oauth/authorize` |
| 토큰 발급 | `https://j15a207.p.ssafy.io/oauth/token` |
| Agent 공개키 | `https://j15a207.p.ssafy.io/.well-known/jwks.json` |
| Core 업무 API origin | `http://core-service:8080` (Docker 내부) |
| ChatGPT 콜백 | ChatGPT 연결 설정에서 확인한 정확한 callback URL |
| App Link | 앱 담당자가 확정할 구매 화면의 HTTPS 기본 경로 (끝에 `/` 필요) |

App Link는 MCP 연결 주소가 아니다. 구매 요청 ID를 붙여 코펜하겐 앱을 열면 앱이 Core에서
구매 요청을 조회한다. Core가 제공하는 OAuth 로그인·동의 HTML은 기존 앱 계정으로
로그인하여 ChatGPT 연결 권한을 부여하는 별도 진입 화면이다.

현재 `mobile/app/src/main/AndroidManifest.xml`에는 MAIN/LAUNCHER 진입점만 있으며,
구매 화면을 외부에서 여는 VIEW/BROWSABLE intent-filter는 없다. APK 설치와 외부 구매
화면 진입은 별도 설정이다. 앱 담당자가 HTTPS App Link 수신 경로, purchaseRequestId
전달 방식, 로그인 후 구매 화면 복원을 구현해야 한다. 도메인·경로는 아직 미정이며
현재 MCP의 HTTPS URL 검증을 임의의 intent 또는 커스텀 스킴으로 우회하지 않는다.

`CORE_TOKEN_URI`는 위 공개 HTTPS 토큰 주소를 사용한다. MCP는 이 URL을 client assertion의
audience에도 사용하므로 임의로 `http://core-service:8080/oauth/token`으로 바꾸면 Core의
검증값과 달라진다. MCP 컨테이너에서도 공개 도메인의 DNS/TLS 접근이 가능해야 한다.

### 공개 주소 기본값과 기존 서버 설정

`docker-compose.yml`만 실행하는 로컬 환경은 `http://localhost:8080` issuer와
`http://localhost:8080/mcp` audience를 기본값으로 사용한다. 개발 EC2에서는
`infra/docker/https.override.yml`을 함께 적용하여 두 값이 미설정 또는 빈 값이면
위 표의 공개 HTTPS 주소를 사용한다(S15P21A207-406).

서버 `.env`에 `AGENT_OAUTH_ISSUER`나 `AGENT_OAUTH_MCP_AUDIENCE`를 명시했다면 그 값이
우선한다. 기존 값이 localhost라면 공개 주소로 수정해야 한다. 배포는 `.env`의 운영자
설정을 덮어쓰지 않는다. 예제 파일을 변경하거나 Nginx만 재시작해도 실행 중인 Core의
OAuth 주소는 바뀌지 않으므로 Core 컨테이너 재생성이 필요하다.

HTTPS override 변경은 CI에서 Core 테스트·빌드를 선택하며 develop 병합 후에는 smoke를
통과한 뒤 Core 이미지와 컨테이너도 갱신한다. Nginx 설정 파일만 변경하면 기존처럼
Nginx 배포만 수행한다. 작업 브랜치에서는 검증만 실행한다.

배포 후 아래 명령으로 비밀정보 없이 공개 주소 네 항목을 확인한다.

```bash
curl --fail --silent --show-error \
  https://j15a207.p.ssafy.io/.well-known/oauth-authorization-server \
  | jq '{issuer, authorization_endpoint, token_endpoint, jwks_uri}'
```

모든 주소가 `https://j15a207.p.ssafy.io`를 사용해야 한다. 여전히 localhost라면
`core-deploy-dev` 성공 여부와 서버 `.env`의 두 OAuth 주소 설정을 확인한다. 주소 수정만으로
MCP 인증 프로필이나 서비스 키가 활성화되지는 않는다.

## 1. 서버 전용 설정과 키

기존 `<DEV_DEPLOY_PATH>/.env`에 [ec2.env.example](ec2.env.example)의 OAuth 항목을 반영한다.
기존 DB·인증서·이미지 설정을 예제 파일로 덮어쓰지 않는다. Compose는 `.env`를 치환에
사용하고 `docker-compose.yml`에 명시한 값만 컨테이너에 전달한다. 예제 파일은 실제 값을
포함하지 않으며 CI는 운영 `.env`와 키를 저장소에서 복사하지 않는다.

| 주체 | 개인키 형식/설정 | 공개키 등록 위치 |
| --- | --- | --- |
| Core Agent OAuth | P-256 EC PKCS#8 DER의 Base64URL, `AGENT_OAUTH_JWT_PRIVATE_KEY` | Core `/.well-known/jwks.json`이 자동 제공 |
| MCP 서비스 클라이언트 | `kid`가 있는 P-256 EC 또는 RSA 2048bit 이상 개인 JWK 파일 | Core DB `oauth_clients.client_jwks`의 JWK Set |

두 키는 서로 다른 키이며 앱 로그인용 `CORE_AUTH_JWT_PRIVATE_KEY`와도 별개다.
Core 키를 비우면 현재 Core 구현은 시작할 때마다 새 키를 생성하므로 서버에서는 고정된
키와 `AGENT_OAUTH_JWT_KEY_ID`를 지정한다. 개인키를 콘솔에 출력하거나 MR·채팅에 붙이지 않는다.

MCP 전용 비밀 디렉터리에 `client.jwk`만 배치한다. `MCP_SECRETS_DIR`은 이 디렉터리의
호스트 절대경로이며 컨테이너의 `/run/secrets/mcp`에 읽기 전용으로 마운트된다.
Core 개인키, 서버 `.env`, TLS 인증서는 이 디렉터리에 넣지 않는다.

```bash
# 해당 이미지의 app UID/GID 확인 후 키 소유권/읽기 권한을 지정한다.
# root 전용 0600 파일을 그대로 마운트하면 비-root app 사용자는 읽을 수 없다.
# MCP_IMAGE에는 서버 .env의 해당 배포 이미지명을 먼저 지정한다.
docker run --rm --entrypoint id "$MCP_IMAGE" app

# 서버 .env에 MCP_SECRETS_DIR를 지정한 후, 실제 컨테이너 사용자로 읽기 가능 여부 확인
docker compose --env-file .env -f docker-compose.yml -f infra/docker/https.override.yml \
  run --rm --no-deps --entrypoint sh mcp-server \
  -c 'test -r /run/secrets/mcp/client.jwk && test -s /run/secrets/mcp/client.jwk'
```

기본값 `./.secrets/mcp`는 Git에서 제외된다. 기존 비밀 디렉터리를 쓰는 경우 소유권은 그
디렉터리의 실제 운영 방식에 맞춘다. 키 파일 내용을 출력하는 `cat`이나 전체 환경이
노출되는 `docker inspect`/`docker compose config` 결과를 공유하지 않는다.

## 2. Core OAuth 클라이언트 등록

Core V10 마이그레이션은 클라이언트를 생성하지만 실제 연결값을 알 수 없어 아래 값은
운영 등록이 필요하다. 마이그레이션 파일을 수정해도 기존 DB 행은 갱신되지 않는다.

- `chatgpt-plugin`: ChatGPT에서 표시되는 실제 콜백 URI를 `redirect_uris` JSON 배열에
  정확히 등록한다. 초기 `https://chat.openai.com/aip/PLACEHOLDER/oauth/callback`을 그대로
  사용하지 않는다. Actions용 주소를 MCP callback으로 추정하지 않는다.
- `copenhagen-mcp`: `client.jwk`에 대응하는 **공개키만** `{"keys":[...]}` JWK Set으로
  `client_jwks`에 등록한다. `kid`와 서명 알고리즘이 일치해야 하며 개인 필드 `d`, `p`, `q`
  등은 등록하지 않는다. 초기 `NULL`이면 client_credentials 발급이 거부된다.
- 사용자 클라이언트는 PUBLIC/PKCE S256 방식이다. 서비스 클라이언트는
  CONFIDENTIAL/private_key_jwt 방식이다. OpenAI API 키나 터널 런타임 키를 사용하지 않는다.

위 DB 값은 Core 담당자가 등록하고 실제 계정으로 로그인·동의를 검증한다. 비밀번호나
발급된 access/refresh token을 MCP 설정 파일에 고정해서 우회하지 않는다.

## 3. 적용 순서

1. 고정 Core 키, MCP 키·공개키 등록, 실제 callback과 App Link를 준비한다.
2. Core의 `AGENT_OAUTH_ISSUER`·`AGENT_OAUTH_MCP_AUDIENCE`를 공개 주소로 지정하고 Core를 배포한다.
3. Nginx 변경을 배포하여 OAuth discovery·JWKS·로그인·토큰 요청을 Core로 전달한다.
4. 서버 `.env`에서 `MCP_SPRING_PROFILES_ACTIVE=core-integration`을 활성화하고 MCP를 재배포한다.
5. 아래 외부 검증 후 ChatGPT 연결을 새로고침하고 새 대화에서 확인한다.

기존 develop CI/CD를 사용한다. 작업 브랜치 수동 배포 job은 추가하지 않는다.
`.env`만 변경하면 실행 중인 컨테이너에는 적용되지 않으므로 재생성이 필요하다.
누락된 필수값이 있으면 MCP는 시작을 거부하며 기존 배포 스크립트가 롤백을 시도한다.
App Link는 OAuth 프로토콜에는 필요 없지만 현재 `core-integration` 프로필의 구매 인계
초기화에서 요구하므로, 앱 담당자 확정 전 임의의 URL로 대체하지 않는다.

## 4. 외부 연결 검증

| 요청 | 기대 결과 |
| --- | --- |
| GET `/.well-known/oauth-protected-resource/mcp` | 200, resource와 authorization_servers가 공개 HTTPS 주소 |
| GET `/.well-known/oauth-authorization-server` | 200, issuer·authorize·token·jwks 주소가 동일 배포와 일치 |
| GET `/.well-known/jwks.json` | 200, 등록한 Core key ID의 공개키 |
| 인증 없는 POST `/mcp` | 401, WWW-Authenticate에 resource_metadata URL 포함 |
| `/internal`, `/internal/v1/catalog/products` | 외부에서는 404 유지 |

200/401 확인만으로 로그인 완료라고 판단하지 않는다. ChatGPT에서 OAuth 로그인·동의·콜백과
토큰 발급을 완료하고 도구 목록 조회, 검색, 장바구니를 실제 Core로 호출해야 한다.
Core `/internal/v1` 업무 API 준비 여부도 별도로 확인한다. S25 새 대화에서 새 연결을 선택해
`코펜하겐 열어줘`로 시작한다. 기존 `Copenhagen QA4` 로컬 Mock 연결과 구분한다.

로컬 검증: `python scripts/test-mcp-deployment.py`는 Docker daemon 없이 Compose의 실제
치환 결과와 환경변수·읽기 전용 키 마운트를 확인한다. `python scripts/test-nginx-config.py`는
Docker에서 임시 인증서와 구분 가능한 합성 upstream을 띄워 HTTP/HTTPS 전달 대상을 검증한다.
운영 계정, 인증서, DB에는 접근하지 않으며 GitLab `nginx-config-test`에서 두 검증을 실행한다.
