# GitLab CI/CD와 EC2 직접 배포

이 레포는 GitHub Actions가 아니라 GitLab CI/CD를 사용합니다. SSAFY GitLab에 Container Registry가 없으므로 Docker image를 Registry에 push하지 않습니다. GitLab CI가 image를 압축파일로 만들고 SSH를 통해 개발 EC2에 직접 전송합니다.

```text
develop merge
  → 변경 서비스 test/build
  → Docker image 생성
  → image.tar.gz artifact 생성
  → SCP로 EC2 전송
  → EC2에서 docker image load
  → 해당 컨테이너만 재생성
  → health check
```

production 배포는 정의되어 있지 않습니다.

## 언제 무엇이 실행되는가

| 발생한 일 | lint/test/build | Docker image artifact | 개발 EC2 배포 |
| --- | --- | --- | --- |
| `develop` 대상 MR 생성·갱신 | 변경 영향 job만 실행 | 실행 안 함 | 실행 안 함 |
| MR이 `develop`에 merge됨 | 변경 영향 job만 다시 실행 | 변경 영향 image만 생성 | 변경 영향 service만 갱신 |
| `develop` 직접 push | 변경 영향 job만 실행 | 변경 영향 image만 생성 | 변경 영향 service만 갱신 |
| 다른 branch push | pipeline 생성 안 함 | 실행 안 함 | 실행 안 함 |

전체 stage 순서는 다음과 같습니다.

```text
lint → test → build → docker-build → deploy-dev
```

MR에서는 `lint`, `test`, `build`까지만 실행합니다. Docker image 생성과 EC2 전송은 merge 뒤 `develop` push pipeline에서만 실행합니다.

## 서비스별 독립 job

| Pipeline | 대표 변경 감지 경로 | EC2에 저장되는 image |
| --- | --- | --- |
| Core | `backend/apps/core-service/**`, `ucp-contract`, `internal-api-contract` | `agentic-commerce/core-service:<commit-sha>` |
| MCP | `backend/apps/mcp-server/**`, `internal-api-contract` | `agentic-commerce/mcp-server:<commit-sha>` |
| Pay | `backend/apps/pay-service/**`, `payment-handler-contract`, `internal-api-contract` | `agentic-commerce/pay-service:<commit-sha>` |
| Merchant Mock | `backend/mocks/ucp-merchant-mock/**`와 관련 계약 | `agentic-commerce/ucp-merchant-mock:<commit-sha>` |
| PG Mock | `backend/mocks/pg-mock/**`와 관련 계약 | `agentic-commerce/pg-mock:<commit-sha>` |

Core만 바뀌면 Core의 test, build, Docker image 생성, EC2 재시작만 실행됩니다. 정확한 변경 경로는 각 `*.gitlab-ci.yml`의 `rules:changes`가 기준입니다. Gradle 공통 설정이나 `test-support`처럼 여러 서비스에 영향을 주는 파일은 관련 pipeline을 모두 실행할 수 있습니다.

## Docker image 전달 방식

`docker-build` job은 다음 작업을 합니다.

1. 변경 서비스의 Dockerfile로 image를 만듭니다.
2. commit SHA를 image tag로 사용합니다.
3. `docker image save`와 gzip으로 `docker-images/<service>.tar.gz`를 만듭니다.
4. 압축파일을 GitLab job artifact로 하루 동안 보관합니다.

`deploy-dev` job은 해당 artifact만 내려받아 EC2로 전송합니다. EC2에서는 `docker image load` 후 다음 명령과 같은 방식으로 대상 서비스만 재생성합니다.

```bash
docker compose up -d --no-deps --no-build --wait <service-name>
```

GitLab Container Registry, AWS ECR, Docker Hub login은 필요하지 않습니다. 다만 image 전체를 매번 전송하므로 Registry 방식보다 전송량이 클 수 있습니다.

## MR merge를 막는 GitLab 설정

모든 필수 pipeline이 성공해야 merge할 수 있게 하려면 Maintainer 또는 Owner가 설정해야 합니다.

1. `Settings → Merge requests → Merge checks`에서 **Pipelines must succeed**를 켭니다.
2. `Settings → Repository → Branch rules`에서 `develop`을 protected branch로 만듭니다.
3. `Allowed to merge`에는 팀 정책에 맞는 역할을 지정합니다.
4. `Allowed to push and merge`는 명시적으로 **No one**으로 설정합니다.
5. 필요하다면 approval rule과 CODEOWNERS approval을 추가합니다.

레포 파일만으로 GitLab의 merge 권한을 강제할 수는 없습니다. 설정 후 일부러 lint를 실패시키는 작은 MR로 merge button이 실제로 막히는지 한 번 확인합니다.

## 사용자가 GitLab에 등록할 변수

`Settings → CI/CD → Variables`에서 다음 값을 등록합니다.

| 변수 | GitLab 타입 | 사용자가 넣을 값 |
| --- | --- | --- |
| `DEV_DEPLOY_HOST` | Variable | 개발 EC2의 public DNS 또는 접근 가능한 IP |
| `DEV_DEPLOY_USER` | Variable | EC2에 SSH 접속할 Linux 사용자 |
| `DEV_DEPLOY_PATH` | Variable | EC2 배포 디렉터리의 절대 경로 |
| `DEV_SSH_PRIVATE_KEY` | File | EC2 배포 사용자의 SSH private key 전체 내용 |
| `DEV_SSH_KNOWN_HOSTS` | File | 확인된 EC2 SSH host key |
| `DEV_DEPLOY_WAIT_TIMEOUT` | Variable, 선택 | health check 대기 초, 기본값 `120` |

예시 값의 모양만 참고하고 실제 값은 레포에 적지 않습니다.

```text
DEV_DEPLOY_HOST=<EC2 public DNS 또는 IP>
DEV_DEPLOY_USER=<SSH 사용자>
DEV_DEPLOY_PATH=<EC2의 절대 배포 경로>
DEV_DEPLOY_WAIT_TIMEOUT=120
```

배포 변수는 `develop` pipeline에서만 사용하므로 protected variable로 제한하는 것을 권장합니다. 이 경우 `develop`도 protected branch여야 합니다.

SSH private key는 여러 줄 값이므로 **File 타입**으로 등록하고 마지막에 줄바꿈을 포함합니다. GitLab 정책상 masked 설정이 불가능할 수 있으므로 key 내용을 job log에 출력하지 않습니다. `DEV_SSH_KNOWN_HOSTS`도 File 타입으로 등록하며, 신뢰할 수 있는 환경에서 host fingerprint를 확인한 값을 사용합니다.

Container Registry 관련 변수와 Deploy Token은 등록하지 않습니다.

## GitLab Runner 요구사항

- Gradle job을 실행할 수 있어야 합니다.
- Docker build job은 Docker-in-Docker를 사용하므로 runner가 privileged 실행을 지원해야 합니다.
- job artifact로 각 `.tar.gz` image를 업로드할 수 있어야 합니다.
- runner에서 EC2의 SSH port로 접근할 수 있어야 합니다.

`docker-build` job에서 Docker daemon 연결 오류가 발생하면 SSAFY GitLab Runner가 Docker-in-Docker를 허용하는지 관리자에게 확인해야 합니다. `413 Request Entity Too Large` 또는 artifact 크기 오류가 발생하면 GitLab artifact 제한보다 image가 큰 것이므로 관리자 확인 또는 외부 Registry 전환이 필요합니다.

## EC2에서 실행되는 배포 과정

각 deploy job은 EC2에 다음 파일을 보냅니다.

```text
docker-compose.yml
infra/nginx/nginx.conf
scripts/deploy-dev-service.sh
.deploy/<pipeline>-<job>-<service>.tar.gz
```

배포 스크립트는 다음 순서로 동작합니다.

1. 전송된 압축 image를 EC2 Docker에 load합니다.
2. 예상한 commit SHA tag가 존재하는지 확인합니다.
3. 임시 `.env`에 해당 서비스 image만 기록합니다.
4. 해당 컨테이너만 `--no-deps --no-build`로 재생성합니다.
5. health check가 성공하면 `.env`를 확정합니다.
6. 실패하면 EC2에 남아 있는 이전 image로 복구를 시도합니다.
7. 전송했던 `.tar.gz` 파일을 삭제합니다.

모든 개발 배포 job은 `development-ec2` resource group을 공유합니다. 두 pipeline이 같은 EC2의 Compose 상태를 동시에 변경하지 않도록 직렬화합니다.

## 처음 한 번 준비하는 순서

1. [Docker와 개발 EC2 구성](../docker/README.md)에 따라 EC2에 Docker와 배포 디렉터리를 준비합니다.
2. 첫 배포 merge 전에 EC2에서 `.env`, PostgreSQL 3개, Redis를 준비합니다.
3. GitLab CI/CD Variables 5개와 선택 변수 1개를 등록합니다.
4. EC2 Security Group에서 GitLab Runner가 SSH로 접근할 수 있게 구성합니다.
5. MR pipeline으로 lint/test/build가 성공하는지 확인합니다.
6. `develop` merge 후 `docker-build` artifact와 `deploy-dev` job 성공을 확인합니다.
7. EC2에서 `docker compose ps`로 해당 서비스가 `healthy`인지 확인합니다.
8. 모든 애플리케이션이 배포된 뒤 Nginx를 시작합니다.

GitLab 공식 참고 문서:

- [성공한 pipeline을 merge 필수 조건으로 설정](https://docs.gitlab.com/user/project/merge_requests/auto_merge/#require-a-successful-pipeline-for-merge)
- [Protected branch로 MR workflow 강제](https://docs.gitlab.com/user/project/repository/branches/protected/#require-merge-requests)
- [CI/CD에서 SSH key와 known hosts 사용](https://docs.gitlab.com/ci/jobs/ssh_keys/)
- [Job artifact 설정](https://docs.gitlab.com/ci/jobs/job_artifacts/)
