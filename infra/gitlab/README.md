# GitLab CI/CD와 EC2 직접 배포

이 레포는 GitHub Actions가 아니라 GitLab CI/CD를 사용합니다. SSAFY GitLab에 Container Registry가 없으므로 Docker image를 Registry에 push하지 않습니다. GitLab Runner가 image를 빌드하고 압축 스트림을 SSH로 개발 EC2의 Docker에 직접 적재합니다. Docker image artifact는 생성하지 않습니다.

```text
develop merge
  → 변경 서비스 test/build
  → Docker image 생성
  → docker save | gzip | SSH 스트림 전송
  → EC2에서 gunzip | docker load
  → 해당 컨테이너만 재생성
  → health check
```

production 배포는 정의되어 있지 않습니다.

## 언제 무엇이 실행되는가

| 발생한 일 | lint/test/build | 배포 스모크 테스트 | Docker image EC2 전송·배포 |
| --- | --- | --- | --- |
| `develop` 대상 MR 생성·갱신 | 변경 영향 job만 실행 | Backend·배포 설정 변경 시 전체 스택 검증 | 실행 안 함 |
| MR이 `develop`에 merge됨 | 변경 영향 job만 다시 실행 | 실행 안 함 | 변경 영향 service만 갱신 |
| `develop` 직접 push | 변경 영향 job만 실행 | 실행 안 함 | 변경 영향 service만 갱신 |
| 다른 branch push | pipeline 생성 안 함 | 실행 안 함 | 실행 안 함 |

전체 stage 순서는 다음과 같습니다.

```text
lint → test → build → deploy-smoke → docker-build → deploy-dev
```

MR에서는 `lint`, `test`, `build`와 변경 경로에 따른 `deploy-smoke`를 실행합니다. 스모크용 Docker image는 job 내부에서 생성·실행·삭제하며 EC2로 전송하지 않습니다. 개발 EC2용 image 생성·전송·배포는 merge 뒤 `develop` push pipeline에서만 실행합니다.

## MR 배포 스모크 테스트

`smoke.gitlab-ci.yml`의 `deploy-smoke` job은 앞선 stage가 성공한 뒤 `scripts/deploy-smoke.sh`를 실행합니다. 기존 SSH 배포 템플릿과 배포 변수는 사용하지 않습니다.

```text
MR 이미지 빌드 → 빈 DB에서 전체 스택 기동·HTTP 검증
  → 임시 볼륨 초기화 → 대상 브랜치 버전 기동·검증 데이터 생성
  → DB를 유지한 MR 이미지 순차 교체 → HTTP·데이터 유지 검증
  → 실패 이미지 배포·이전 이미지 복구 검증 → 로그 수집·환경 정리
```

* 실제 서비스 Dockerfile과 `docker-compose.yml`을 재사용합니다. Core·Pay·MCP·Merchant Mock·PG Mock, PostgreSQL 3개, Redis, Nginx를 실행합니다.
* Nginx만 `infra/docker/smoke.override.yml`로 호스트 포트와 bind mount를 제거합니다. 원본 Compose의 Nginx image에 동일 `nginx.conf`를 복사하므로 Docker-in-Docker에서도 job 파일 경로를 공유할 필요가 없습니다.
* 매 실행마다 고유한 Compose 프로젝트 이름과 image tag, 임시 DB 비밀번호를 생성합니다. 개인 `.env`, 개발 EC2 key, 실제 DB를 사용하지 않습니다.
* 애플리케이션 health 응답, Nginx 설정 및 upstream 경로, 컨테이너 안의 실제 서비스 URL을 통한 health 요청과 Core 컨테이너에서의 Merchant Profile 조회를 검증합니다.
* 현재 Core/MCP 업무 API는 아직 없으므로 Nginx 경로는 존재하지 않는 `__smoke__` 경로의 **애플리케이션 JSON 404와 원본 path**로 upstream 도달을 확인합니다. Nginx 자체 404나 502는 통과하지 않습니다. 이 결과는 결제 업무 흐름 전체의 E2E 성공을 의미하지 않습니다.
* DB 검증은 임시 schema의 표식 데이터 유지와 애플리케이션 기동 시 Flyway 검증을 확인합니다. 실제 업무 데이터의 모든 마이그레이션 호환성은 서비스별 테스트로 보완해야 합니다.
* 이미지 교체·복구에는 `deploy-dev-service.sh`를 사용합니다. 실행 중인 Nginx가 있으면 설정 검사와 reload로 바뀐 upstream IP를 반영합니다. 복구 성공은 반환 코드만으로 판단하지 않고 이전 image·`.env`·health·HTTP 응답을 다시 확인합니다. DB schema 원복은 수행하지 않습니다.

실행 대상은 `backend/**/*`, 루트 CI/Compose, `infra/gitlab/**/*`, `infra/nginx/**/*`, smoke override와 스모크·배포 스크립트 변경입니다. 이 경로 안의 README 변경도 포함하며, 그 외 문서만 바뀌면 생략합니다.

### 검증하는 Git 버전

기준 버전은 job 시작 시 가져온 대상 브랜치 SHA입니다. Merged results pipeline에서는 GitLab이 제공하는 대상 SHA를 사용합니다. 기준 SHA가 검사 대상 commit의 조상이 아니면 스모크 테스트를 실패시키므로, 일반 MR pipeline에서도 최신 `develop`을 rebase한 뒤 다시 검증해야 합니다.

GitLab 인스턴스가 Merged results pipelines를 지원하면 프로젝트 Merge options에서 활성화할 수 있습니다. 지원하지 않으면 위 조상 검사와 merge 직전 대상 브랜치 동기화·재실행을 사용합니다. 파이프라인 실행 후 대상 브랜치가 변경되는 상황까지 로컬 스크립트가 막아주지는 않습니다.

### 실행 환경과 결과

Runner는 기존 image 빌드 job과 마찬가지로 privileged Docker-in-Docker를 지원해야 합니다. Compose 2.24.4 이상과 BuildKit이 필요하며 Bash·Git·jq는 job에서 설치합니다. 최초 실행은 기준 버전과 MR 버전의 image를 모두 빌드하므로 의존성 다운로드와 전체 스택을 실행할 메모리가 필요합니다. Job 제한 시간은 30분, 서비스 기동 대기는 기본 180초입니다.

로컬에서는 레포 루트에서 다음과 같이 실행합니다.

```bash
git fetch origin develop
SMOKE_BASE_REF=origin/develop bash scripts/deploy-smoke.sh
```

`SMOKE_WAIT_TIMEOUT`으로 기동 대기 시간을, `SMOKE_ARTIFACT_DIR`로 결과 디렉터리를 변경할 수 있습니다. 기본 결과 경로는 Git에서 제외되는 `tmp/deploy-smoke/`이며 CI는 성공·실패 모두 이 경로를 1일간 artifact로 보관합니다. 결과에는 검사 SHA, 단계별 판정, 컨테이너 상태·로그·health 결과가 남습니다. 환경 변수 전체나 `.env`는 artifact에 포함하지 않습니다.

정상 종료·실패·종료 신호에서 해당 테스트의 컨테이너·네트워크·볼륨·image를 정리합니다. Runner 강제 종료처럼 종료 처리가 불가능한 경우는 job 전용 Docker-in-Docker 서비스 폐기에 의존합니다. 공유 daemon의 전체 prune은 실행하지 않습니다.

### Merge 차단과 검증 범위

Job은 `allow_failure: false`입니다. 아래 **Pipelines must succeed** 설정까지 적용되어야 실패한 MR의 merge를 막을 수 있습니다. Dockerfile 빌드 오류, unhealthy 서비스, 잘못된 Nginx upstream을 각각 임시 작업 브랜치에 주입하여 실패 job과 merge 차단을 확인합니다.

스모크 환경에서는 기준 Nginx를 유지한 서비스 교체·reload를 검증한 뒤 MR의 Nginx 설정 image로 재생성합니다. 실제 EC2의 SSH 권한·디스크·네트워크와 이미 배포된 서비스별 SHA의 일치까지 보장하지 않습니다. Nginx 설정만 변경한 MR은 스모크 검증 대상이지만 기존 개발 배포 job의 변경 경로에는 포함되지 않으므로 EC2 반영은 별도 확인해야 합니다. 실제 서버 검증을 추가하려면 독립된 스모크용 EC2를 사용합니다.

## 서비스별 독립 job

| Pipeline | 대표 변경 감지 경로 | EC2에 저장되는 image |
| --- | --- | --- |
| Core | `backend/apps/core-service/**`, `ucp-contract`, `internal-api-contract` | `copenhagen/core-service:<commit-sha>` |
| MCP | `backend/apps/mcp-server/**`, `internal-api-contract` | `copenhagen/mcp-server:<commit-sha>` |
| Pay | `backend/apps/pay-service/**`, `payment-handler-contract`, `internal-api-contract` | `copenhagen/pay-service:<commit-sha>` |
| Merchant Mock | `backend/mocks/ucp-merchant-mock/**`와 관련 계약 | `copenhagen/ucp-merchant-mock:<commit-sha>` |
| PG Mock | `backend/mocks/pg-mock/**`와 관련 계약 | `copenhagen/pg-mock:<commit-sha>` |

Core만 바뀌면 Core의 test, build, Docker image 생성, EC2 재시작만 실행됩니다. 정확한 변경 경로는 각 `*.gitlab-ci.yml`의 `rules:changes`가 기준입니다. Gradle 공통 설정이나 `test-support`처럼 여러 서비스에 영향을 주는 파일은 관련 pipeline을 모두 실행할 수 있습니다.

## Docker image 전달 방식

`docker-build` job은 다음 작업을 합니다.

1. 변경 서비스의 Dockerfile로 image를 만듭니다.
2. commit SHA를 image tag로 사용합니다.
3. `docker save "$CONTAINER_IMAGE" | gzip -1 | ssh ... "bash -o pipefail -c 'gunzip | docker load'"`로 EC2에 직접 적재합니다.
4. Runner와 EC2 양쪽에서 `pipefail`을 적용해 저장·압축·SSH·압축 해제·적재 실패를 job 실패로 처리합니다.

`deploy-dev` job은 해당 서비스의 빌드·전송 성공을 기다리고 artifact를 내려받지 않습니다. EC2에 이미 적재된 image를 확인한 뒤 다음 명령과 같은 방식으로 대상 서비스만 재생성합니다.

```bash
docker compose up -d --no-deps --no-build --pull never --wait <service-name>
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

Container Registry 관련 변수와 Deploy Token은 등록하지 않습니다. 빌드·전송 job도 `development` 환경의 `prepare` 작업으로 실행하므로 기존 환경 범위 배포 변수를 재사용합니다.

## GitLab Runner 요구사항

- Gradle job을 실행할 수 있어야 합니다.
- Docker build job은 Docker-in-Docker를 사용하므로 runner가 privileged 실행을 지원해야 합니다.
- runner에서 EC2의 SSH port로 접근할 수 있어야 합니다.

`docker-build` job에서 Docker daemon 연결 오류가 발생하면 SSAFY GitLab Runner가 Docker-in-Docker를 허용하는지 관리자에게 확인해야 합니다. Docker image를 artifact로 업로드하지 않으므로 image 전달에 GitLab artifact 크기 제한이 적용되지 않습니다.

EC2에는 Bash와 gunzip이 필요하며, 배포 SSH 사용자가 sudo 없이 Docker를 실행할 수 있어야 합니다.

## EC2에서 실행되는 배포 과정

각 deploy job은 EC2에 다음 파일을 보냅니다.

```text
docker-compose.yml
infra/nginx/nginx.conf
scripts/deploy-dev-service.sh
```

배포 스크립트는 다음 순서로 동작합니다.

1. 빌드 job이 SSH로 적재한 commit SHA tag가 존재하는지 확인합니다.
2. 임시 `.env`에 해당 서비스 image만 기록합니다.
3. 해당 컨테이너만 `--no-deps --no-build`로 재생성합니다.
4. health check가 성공하면 실행 중인 Nginx의 설정을 검사하고 reload하여 새 upstream 주소를 반영한 뒤 `.env`를 확정합니다. 최초 배포처럼 Nginx가 아직 실행 중이 아니면 이 단계를 생략합니다.
5. 기동 또는 Nginx 검사·reload가 실패하면 실패 컨테이너 로그를 출력하고, EC2에 남아 있는 이전 image로 복구한 뒤 Nginx reload를 다시 시도합니다. DB와 Nginx 설정 파일 자체를 이전 버전으로 되돌리지는 않습니다.

모든 개발 배포 job은 `development-ec2` resource group을 공유합니다. 두 pipeline이 같은 EC2의 Compose 상태를 동시에 변경하지 않도록 직렬화합니다.

## 처음 한 번 준비하는 순서

1. [Docker와 개발 EC2 구성](../docker/README.md)에 따라 EC2에 Docker와 배포 디렉터리를 준비합니다.
2. 첫 배포 merge 전에 EC2에서 `.env`, PostgreSQL 3개, Redis를 준비합니다.
3. GitLab CI/CD Variables 5개와 선택 변수 1개를 등록합니다.
4. EC2 Security Group에서 GitLab Runner가 SSH로 접근할 수 있게 구성합니다.
5. MR pipeline으로 lint/test/build가 성공하는지 확인합니다.
6. `develop` merge 후 `docker-build`의 SSH 적재와 `deploy-dev` job 성공을 확인합니다.
7. EC2에서 `docker compose ps`로 해당 서비스가 `healthy`인지 확인합니다.
8. 모든 애플리케이션이 배포된 뒤 Nginx를 시작합니다.

GitLab 공식 참고 문서:

- [성공한 pipeline을 merge 필수 조건으로 설정](https://docs.gitlab.com/user/project/merge_requests/auto_merge/#require-a-successful-pipeline-for-merge)
- [Protected branch로 MR workflow 강제](https://docs.gitlab.com/user/project/repository/branches/protected/#require-merge-requests)
- [CI/CD에서 SSH key와 known hosts 사용](https://docs.gitlab.com/ci/jobs/ssh_keys/)
- [Job artifact 설정](https://docs.gitlab.com/ci/jobs/job_artifacts/)
