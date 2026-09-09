# 공용 스크립트

레포 전체에서 사용하는 개발 서버 준비 및 배포 스크립트입니다. 인자 없이 내용을 추측해 실행하지 말고 아래 용도를 확인합니다.

## `bootstrap-dev-infra.sh`

개발 EC2에서 최초 한 번 PostgreSQL 3개와 Redis를 시작합니다.

```bash
./scripts/bootstrap-dev-infra.sh [compose-file] [env-file]
```

기본값은 `docker-compose.yml`과 `.env`입니다. `.env`에 `CHANGE_ME`가 남아 있으면 DB를 시작하지 않습니다. 애플리케이션이나 Nginx는 이 스크립트가 시작하지 않습니다.

## `deploy-dev-service.sh`

GitLab deploy job이 한 서비스만 갱신할 때 사용합니다.

```bash
./scripts/deploy-dev-service.sh \
  <compose-service> \
  <image-variable> \
  <image> \
  [compose-file] \
  [wait-timeout]
```

허용된 service와 image 변수 조합만 실행합니다. CI가 SSH 스트림으로 미리 적재한 image가 존재하는지 확인하고 `--no-deps --no-build --pull never`로 대상 컨테이너만 재생성합니다. 실행 중인 Nginx가 있으면 설정 검사와 reload로 새 upstream IP를 반영합니다. 기동 또는 Nginx 검사·reload 실패 시 컨테이너 로그를 출력하고, EC2에 이전 image가 남아 있으면 복구와 Nginx reload를 시도합니다.

일반 개발자가 로컬 코드를 반영하려고 이 배포 스크립트를 실행할 필요는 없습니다. 로컬에서는 `docker compose up --build`를 사용하고, 개발 EC2 배포는 GitLab CI가 담당합니다.

## `deploy-smoke.sh`

MR에서 배포 전에 실제 Docker image와 전체 Compose 스택을 검증합니다. 빈 DB 기동, 기준 버전에서 MR 버전으로의 업데이트, 실패 image 복구를 테스트하고 임시 자원을 정리합니다.

```bash
git fetch origin develop
SMOKE_BASE_REF=origin/develop bash scripts/deploy-smoke.sh
```

Docker/BuildKit, Compose 2.24.4 이상, Bash, Git, jq가 필요합니다. 개인 `.env`나 개발 EC2에 접근하지 않으며 결과는 기본 `tmp/deploy-smoke/`에 저장합니다. 설정과 검증 범위는 [MR 배포 스모크 테스트](../infra/gitlab/README.md#mr-배포-스모크-테스트)를 참고합니다.
