# GitLab CI/CD와 개발 서버 배포

이 레포는 GitHub Actions가 아니라 GitLab CI/CD를 사용합니다. 루트 `.gitlab-ci.yml`은 이 디렉터리의 공통, Core, MCP, Pay, Mock pipeline 정의만 include합니다. production 배포는 정의되어 있지 않습니다.

## 언제 무엇이 실행되는가

| 발생한 일 | lint/test/build | Docker build와 Registry push | 개발 EC2 배포 |
| --- | --- | --- | --- |
| `develop`을 target으로 한 MR 생성·갱신 | 변경 영향 job만 실행 | 실행 안 함 | 실행 안 함 |
| MR이 `develop`에 merge됨 | 변경 영향 job만 다시 실행 | 변경 영향 image만 실행 | 변경 영향 service만 실행 |
| `develop` 직접 push | 변경 영향 job만 실행 | 변경 영향 image만 실행 | 변경 영향 service만 실행 |
| 다른 branch push | pipeline 생성 안 함 | 실행 안 함 | 실행 안 함 |

전체 stage 순서는 다음과 같습니다.

```text
lint → test → build → docker-build → deploy-dev
```

MR에서는 앞의 세 stage까지만 실행합니다. merge 뒤 `develop` push pipeline에서만 image 생성과 배포를 수행합니다.

## 독립 실행되는 service job

| Pipeline | 대표 변경 감지 경로 | 생성 image |
| --- | --- | --- |
| Core | `backend/apps/core-service/**`, `ucp-contract`, `internal-api-contract` | `$CI_REGISTRY_IMAGE/core-service:$CI_COMMIT_SHA` |
| MCP | `backend/apps/mcp-server/**`, `internal-api-contract` | `$CI_REGISTRY_IMAGE/mcp-server:$CI_COMMIT_SHA` |
| Pay | `backend/apps/pay-service/**`, `payment-handler-contract`, `internal-api-contract` | `$CI_REGISTRY_IMAGE/pay-service:$CI_COMMIT_SHA` |
| Merchant Mock | `backend/mocks/ucp-merchant-mock/**`와 관련 계약 | `$CI_REGISTRY_IMAGE/ucp-merchant-mock:$CI_COMMIT_SHA` |
| PG Mock | `backend/mocks/pg-mock/**`와 관련 계약 | `$CI_REGISTRY_IMAGE/pg-mock:$CI_COMMIT_SHA` |

공통 Gradle 설정, Wrapper, Compose 또는 해당 CI 정의가 바뀌어도 영향받는 job이 실행됩니다. 정확한 경로 목록은 각 `*.gitlab-ci.yml`의 `rules:changes`가 기준입니다. `test-support`처럼 여러 서비스에 영향을 주는 공용 경로는 관련 pipeline을 모두 실행할 수 있습니다.

## MR에서 merge 전에 검사하는 것

- `repository-lint`: whitespace 오류 등 Git diff 기본 품질 검사
- `backend-lint`: Backend 변경 시 전체 Java Checkstyle 검사
- `<service>-test`: 변경 영향 서비스 테스트
- `<service>-build`: 테스트를 통과한 서비스의 실행 JAR 생성

모든 필수 pipeline이 성공해야만 merge할 수 있게 하려면 GitLab 프로젝트에서 다음 관리 설정이 필요합니다.

1. `Settings → Merge requests → Merge checks`에서 **Pipelines must succeed**를 켜고 저장합니다.
2. `Settings → Repository → Branch rules`에서 `develop`을 protected branch로 등록합니다.
3. `Allowed to merge`에는 팀 정책에 맞는 역할을 지정하고, `Allowed to push and merge`는 명시적으로 **No one**으로 설정합니다.
4. 필요하다면 approval rule과 CODEOWNERS approval을 추가합니다.

레포 파일만으로는 프로젝트의 merge 권한을 강제할 수 없습니다. 위 설정은 GitLab Maintainer 또는 Owner가 UI에서 해야 합니다. 설정 후 일부러 실패하는 작은 MR로 merge button이 막히는지 한 번 확인합니다.

## 개발 배포에 필요한 GitLab 변수

GitLab 프로젝트의 CI/CD Variables에 다음 값을 등록합니다. private key와 known hosts는 **File 타입**으로 등록합니다.

| 변수 | 형식 | 의미 |
| --- | --- | --- |
| `DEV_DEPLOY_HOST` | 일반 변수 | 개발 EC2 hostname 또는 IP |
| `DEV_DEPLOY_USER` | 일반 변수 | SSH 배포 사용자 |
| `DEV_DEPLOY_PATH` | 일반 변수 | EC2에서 Compose 파일과 `.env`를 둘 절대 경로 |
| `DEV_SSH_PRIVATE_KEY` | File 변수 | CI runner가 사용할 SSH private key |
| `DEV_SSH_KNOWN_HOSTS` | File 변수 | 검증된 EC2 host key |
| `DEV_DEPLOY_WAIT_TIMEOUT` | 선택 일반 변수 | health check 대기 초, 기본값 `120` |

실제 host, 사용자명, password, key를 레포에 넣지 않습니다. `DEV_SSH_KNOWN_HOSTS`는 접속 시 자동 수집한 값을 무검증으로 사용하지 말고 별도 신뢰 경로로 fingerprint를 확인합니다.

배포 변수는 `develop` push pipeline에서만 필요하므로 protected variable로 제한하는 것을 권장합니다. SSH private key는 여러 줄 값이라 GitLab의 masked 조건을 충족하지 못할 수 있습니다. 이 경우 File 타입과 protected 설정을 사용하고, job log에 key 내용을 출력하지 않습니다. private key 값 마지막에는 줄바꿈이 있어야 합니다.

## GitLab Runner 요구사항

- Gradle job은 Java 21 container에서 Wrapper로 실행됩니다.
- Docker build job은 Docker-in-Docker를 사용하므로 runner가 해당 실행 방식을 허용해야 합니다.
- runner에서 GitLab Container Registry에 접근할 수 있어야 합니다.
- deploy job에서 개발 EC2의 SSH port로 통신할 수 있어야 합니다.

Container Registry가 프로젝트에서 활성화되어 있어야 하며, CI의 기본 `CI_REGISTRY_*` 변수로 image를 push합니다.

## EC2 배포가 한 서비스만 갱신하는 방법

각 deploy job은 현재 `docker-compose.yml`, Nginx 설정, `scripts/deploy-dev-service.sh`를 EC2의 `DEV_DEPLOY_PATH`로 복사합니다. 그다음 배포 스크립트가 다음 작업을 수행합니다.

1. 해당 service의 새 commit SHA image만 pull합니다.
2. 임시 환경파일로 해당 service만 `--no-deps --no-build` 재생성합니다.
3. health check 성공 시 새 image 값을 `.env`에 확정합니다.
4. 실패하면 기존 `.env`에 기록된 이전 image로 복구를 시도합니다.

모든 개발 배포 job은 `development-ec2` resource group을 공유합니다. 서로 다른 pipeline이 같은 EC2의 Compose 상태를 동시에 변경하지 못하게 직렬화하기 위한 것입니다.

## 최초 설정 순서

1. EC2와 배포 사용자, Docker를 준비합니다.
2. EC2 `.env`와 Registry login을 준비합니다.
3. `./scripts/bootstrap-dev-infra.sh`로 DB 3개와 Redis를 시작합니다.
4. GitLab CI/CD Variables를 등록합니다.
5. project merge check와 `develop` branch protection을 설정합니다.
6. `develop` 대상 MR에서 lint/test/build가 실행되는지 확인합니다.
7. merge 후 변경된 image만 Registry에 생기고 해당 EC2 service만 갱신되는지 확인합니다.
8. 애플리케이션들이 준비된 뒤 Nginx를 시작합니다.

EC2의 구체적인 준비와 문제 해결은 [Docker와 개발 EC2 구성](../docker/README.md)을 참고합니다.

GitLab 공식 문서:

- [성공한 pipeline을 merge 필수 조건으로 설정](https://docs.gitlab.com/user/project/merge_requests/auto_merge/#require-a-successful-pipeline-for-merge)
- [Protected branch로 MR workflow 강제](https://docs.gitlab.com/user/project/repository/branches/protected/#require-merge-requests)
- [CI/CD에서 SSH key와 known hosts 사용](https://docs.gitlab.com/ci/jobs/ssh_keys/)
