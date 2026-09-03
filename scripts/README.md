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

허용된 service와 image 변수 조합만 실행합니다. 새 image를 pull하고 `--no-deps --no-build`로 대상 컨테이너만 재생성하며, health check 실패 시 이전 image 복구를 시도합니다.

일반 개발자가 로컬 코드를 반영하려고 이 배포 스크립트를 실행할 필요는 없습니다. 로컬에서는 `docker compose up --build`를 사용하고, 개발 EC2 배포는 GitLab CI가 담당합니다.
