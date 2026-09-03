# Docker와 개발 EC2 구성

레포 루트의 `docker-compose.yml` 하나로 로컬 환경과 개발 EC2를 구성합니다. 애플리케이션 5개, PostgreSQL 3개, Redis, Nginx가 각각 별도 컨테이너로 실행됩니다.

## 로컬에서 실행하기

필요한 것은 Docker Engine과 Docker Compose v2입니다. 레포 루트에서 실행합니다.

```bash
docker compose up --build
docker compose ps
```

처음 실행할 때 이미지를 만들고 DB volume을 생성하므로 시간이 걸릴 수 있습니다. 각 애플리케이션이 `healthy`인지 확인합니다.

```bash
docker compose logs -f core-service
docker compose down
```

`docker compose down`은 DB 데이터를 보존합니다. `docker compose down --volumes`는 세 PostgreSQL과 Redis 데이터를 삭제하므로 초기화가 필요할 때만 실행합니다.

## Compose 구성

| Compose service | image 환경변수 | 역할 |
| --- | --- | --- |
| `nginx` | 해당 없음 | 외부 ingress |
| `core-service` | `CORE_IMAGE` | UCP와 구매 흐름 조정 |
| `mcp-server` | `MCP_IMAGE` | Agent-facing adapter |
| `pay-service` | `PAY_IMAGE` | 결제 처리 |
| `ucp-merchant-mock` | `MERCHANT_MOCK_IMAGE` | 외부 Merchant 시뮬레이터 |
| `pg-mock` | `PG_MOCK_IMAGE` | 외부 PG 시뮬레이터 |
| `core-db` | 해당 없음 | Core 전용 PostgreSQL |
| `pay-db` | 해당 없음 | Pay 전용 PostgreSQL |
| `merchant-db` | 해당 없음 | Merchant Mock 전용 PostgreSQL |
| `redis` | 해당 없음 | 아직 용도가 정해지지 않은 공유 인프라 |

로컬에서는 image 환경변수가 없으면 `agentic-commerce/<service>:local`을 만들고 사용합니다. EC2의 `.env`에는 CI가 Registry image의 commit SHA tag를 기록합니다.

## 내부 통신 원칙

컨테이너끼리는 Compose service name을 DNS 이름으로 사용합니다.

```text
mcp-server       → http://core-service:8080
core-service      → http://pay-service:8080
core-service      → http://ucp-merchant-mock:8080
ucp-merchant-mock → http://pay-service:8080
pay-service       → http://pg-mock:8080
```

내부 통신에 EC2 public IP, `localhost`, Nginx 주소를 사용하지 않습니다. Nginx는 외부 요청만 받습니다.

## 개발 EC2 최초 준비

이 과정은 EC2 한 대당 최초 한 번 사람이 실행합니다.

1. Docker Engine과 Docker Compose v2 plugin을 설치합니다.
2. 배포 전용 Linux 사용자를 준비하고 Docker 실행 권한을 부여합니다.
3. 이 레포를 GitLab CI 변수 `DEV_DEPLOY_PATH`로 사용할 절대 경로에 clone하고 `develop`을 checkout합니다.
4. `infra/docker/ec2.env.example`을 그 경로의 `.env`로 복사합니다.
5. `.env`의 모든 `CHANGE_ME`를 실제 개발용 비밀번호로 교체하고 권한을 `600`으로 설정합니다.
6. 배포 사용자가 `DEV_DEPLOY_PATH`, `infra/nginx`, `scripts`에 쓸 수 있는지 확인합니다.
7. `read_registry` 권한의 GitLab Deploy Token으로 EC2의 Docker를 Container Registry에 login합니다.

환경파일 준비 예시입니다. 경로와 값은 실제 환경에 맞게 사용합니다.

```bash
cd <DEV_DEPLOY_PATH>
cp infra/docker/ec2.env.example .env
chmod 600 .env
```

실제 비밀번호를 채운 `.env`는 Git에 commit하지 않습니다. 개발 서버에는 production credential을 사용하지 않습니다.

## 공유 인프라 최초 실행

DB와 Redis는 애플리케이션 image 배포 대상이 아니므로 최초에 수동으로 한 번 시작합니다.

```bash
cd <DEV_DEPLOY_PATH>
./scripts/bootstrap-dev-infra.sh
```

이 스크립트는 `.env`가 존재하고 `CHANGE_ME`가 남아 있지 않은지 검사한 뒤 `core-db`, `pay-db`, `merchant-db`, `redis`만 pull하고 시작합니다.

그다음 `develop` pipeline이 애플리케이션들을 배포하도록 최초 변경을 merge합니다. Core, MCP, Pay, Mock 컨테이너가 모두 배포된 뒤 Nginx를 한 번 시작합니다.

```bash
docker compose --env-file .env up -d --wait nginx
```

## 이후 자동 배포 방식

`develop`에 변경이 들어오면 CI는 영향받은 서비스에 대해서만 다음 작업을 합니다.

```text
Docker image 생성 → GitLab Registry push → EC2 SSH 접속
→ 새 image만 pull → 해당 service만 재생성 → health check
```

배포는 `docker compose up -d --no-deps --no-build --wait <service>`를 사용합니다. 따라서 Core 배포 때문에 Pay나 DB가 다시 시작되지 않습니다. 성공한 image tag는 EC2의 `.env`에 저장됩니다. health check가 실패하면 스크립트가 이전에 기록된 image로 복구를 시도합니다.

## 문제 확인

```bash
cd <DEV_DEPLOY_PATH>
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=200 <service-name>
docker compose --env-file .env config
```

- `pull access denied`: EC2의 Registry login과 Deploy Token의 `read_registry` 권한을 확인합니다.
- `unhealthy`: 해당 서비스 로그, DB 상태, `.env`의 연결 값을 확인합니다.
- `port is already allocated`: `.env`의 `NGINX_PORT`와 EC2에서 사용 중인 port를 확인합니다.
- SSH 또는 파일 복사 실패: GitLab의 배포 변수, `known_hosts`, 배포 경로 권한을 확인합니다.

CI 변수와 GitLab 설정은 [GitLab CI/CD 문서](../gitlab/README.md)를 참고합니다.
