# GitLab CI/CD와 EC2 직접 배포

- 배포 대상: 개발 EC2. production 배포 미정의
- 이미지 전달: `docker save | gzip | SSH | gunzip | docker load`
- Container Registry, 이미지 artifact 미사용
- 전송 단위: commit SHA 태그의 전체 이미지

## 실행 조건

| 이벤트 | lint/test/build | deploy-smoke | EC2 배포 |
| --- | --- | --- | --- |
| `develop` 대상 MR 생성, 갱신 | 변경 영향 job | Backend 또는 배포 설정 변경 시 전체 스택 | 없음 |
| `develop` push, MR merge 포함 | 변경 영향 job | 없음 | 변경 영향 서비스 |
| 다른 브랜치 push | pipeline 없음 | 없음 | 없음 |

Stage: `lint -> test -> build -> deploy-smoke -> docker-build -> deploy-dev`

## MR 배포 스모크 테스트

- 실행: 앞선 stage 성공 후 `smoke.gitlab-ci.yml`의 `deploy-smoke` job
- 스크립트: `scripts/deploy-smoke.sh`
- 대상: Core, Pay, MCP, Merchant Mock, PG Mock, PostgreSQL 3개, Redis, Nginx
- 구성: 서비스 Dockerfile, `docker-compose.yml` 재사용
- 격리: 실행별 Compose 프로젝트, 이미지 태그, 임시 DB 비밀번호
- 접근 제외: 개인 `.env`, 개발 EC2 키, 실제 DB
- Nginx: smoke override로 호스트 포트와 bind mount 제거, 원본 이미지에 동일 `nginx.conf` 복사

검증 순서:

1. MR 이미지 빌드, 빈 DB 기동, HTTP 검사
2. 임시 볼륨 초기화, 대상 브랜치 버전 기동, 표식 데이터 생성
3. DB 유지 상태에서 MR 이미지 순차 교체, HTTP와 데이터 검사
4. 실패 이미지 배포, 이전 이미지 복구 검사
5. 로그 수집, 임시 자원 정리

| 항목 | 검증 기준 |
| --- | --- |
| 서비스 | health 응답, 실제 서비스 URL 호출, Core의 Merchant Profile 조회 |
| Nginx | 설정 검사, upstream 경로, 교체 후 reload |
| `__smoke__` 경로 | 애플리케이션 JSON 404와 원본 path 일치. Nginx 자체 404, 502 실패 |
| DB | 표식 데이터 유지, Flyway 기동 검사 |
| 복구 | `deploy-dev-service.sh` 실행 후 이전 이미지, `.env`, health, HTTP 확인 |

검증 제외: 결제 전체 E2E, 업무 데이터의 전체 마이그레이션 호환성, DB schema 원복, 실제 EC2의 SSH 권한과 자원 상태, 배포된 서비스별 SHA 일치.

변경 감지 경로: `backend/**/*`, 루트 CI/Compose, `infra/gitlab/**/*`, `infra/nginx/**/*`, smoke override, 스모크/배포 스크립트. 해당 경로의 README 포함. 그 외 문서 변경은 생략.

### Git 버전

- 기준 SHA: job 시작 시 가져온 대상 브랜치 SHA
- Merged results pipeline: GitLab 제공 대상 SHA 사용
- 통과 조건: 기준 SHA가 검사 대상 커밋의 조상
- MR 갱신: 최신 `develop` rebase 후 재실행
- Merge 직전: 대상 브랜치 변경 여부 확인, 변경 시 동기화 후 재실행
- Merged results pipelines: GitLab 지원 시 Merge options에서 활성화

### 실행 환경과 결과

| 항목 | 설정 |
| --- | --- |
| Runner | privileged Docker-in-Docker, 전체 스택 실행 메모리 |
| 도구 | BuildKit, Compose 2.24.4 이상. Bash, Git, jq는 job에서 설치 |
| 최초 빌드 | 기준 버전과 MR 버전 이미지, 의존성 다운로드 |
| Job 제한 | 30분 |
| 기동 대기 | `SMOKE_WAIT_TIMEOUT`, 기본 180초 |
| 결과 경로 | `SMOKE_ARTIFACT_DIR`, 기본 `tmp/deploy-smoke/`, Git 제외 |
| Artifact | 성공과 실패 모두 1일 보관. SHA, 단계별 판정, 상태, 로그, health |

```bash
git fetch origin develop
SMOKE_BASE_REF=origin/develop bash scripts/deploy-smoke.sh
```

종료, 실패, 종료 신호 수신 시 테스트 컨테이너, 네트워크, 볼륨, 이미지 정리. Runner 강제 종료 시 job 전용 Docker-in-Docker 폐기. 공유 daemon 전체 prune 금지.

### 실패 진단

- 출력 순서: 실패 단계와 최초 종료 코드, 상태, health, 최근 로그, 자원 정리
- 성공 시 상세 진단: artifact에만 저장
- 진단 수집 실패: 정리 계속 진행
- 정리 추가 실패: 최초 실패 코드 유지
- 마스킹: 임시 DB 비밀번호 치환 후 파일 저장과 Job 출력
- 로그 제한: 컨테이너별 최근 200줄, 줄당 4096자. health도 줄당 4096자
- 출력 제외: 전체 inspect, 환경 변수 목록, `.env`, HTTP 응답 본문

로그 예시:

```text
FAIL exit=1 phase=fresh-deploy
--- fresh-deploy-containers.txt ---
--- fresh-deploy-health.log ---
/pay-service status=restarting ExitCode=1 OOMKilled=false error= health=unhealthy
--- fresh-deploy-containers.log ---
exit=1 phase=fresh-deploy
```

| HTTP 호출 | 실패 분류 | 표시 항목 |
| --- | --- | --- |
| 직접 curl | `dns`, `connection`, `timeout`, `transport`, `status`, `json` | 대상, 종료 코드 또는 기대/실제 상태 코드, 시간 제한 또는 JSON 조건 |
| 서비스 간 wget | `request`, `json` | 출발 서비스, 대상 URL 변수와 경로, 종료 코드와 시간 제한 또는 JSON 조건 |

```text
FAIL HTTP target=http://pay-service:8080/actuator/health reason=timeout exit=28 connect-timeout=5 max-time=10
FAIL HTTP target=http://nginx/api/pay/__smoke__ reason=status expected=404 actual=502
FAIL HTTP source=core-service target=PAY_SERVICE_URL/actuator/health reason=json assertion=.status == "UP"
```

### Merge 차단과 검증 범위

- 필수 설정: `allow_failure: false`, GitLab `Pipelines must succeed`
- 실패 주입: 임시 브랜치에서 Dockerfile 빌드 오류, unhealthy 서비스, 잘못된 Nginx upstream별 job 실패와 merge 차단 확인
- Nginx 검증: 기준 Nginx에서 서비스 교체와 reload 후 MR Nginx 이미지로 재생성
- Nginx 설정만 변경: 스모크 실행, EC2 반영 별도 확인
- 실제 서버 검증: 독립된 스모크용 EC2 사용

## 서비스별 독립 job

| 서비스 | 대표 변경 경로 | EC2 이미지 |
| --- | --- | --- |
| Core | `backend/apps/core-service/**`, `ucp-contract`, `internal-api-contract` | `copenhagen/core-service:<commit-sha>` |
| MCP | `backend/apps/mcp-server/**`, `internal-api-contract` | `copenhagen/mcp-server:<commit-sha>` |
| Pay | `backend/apps/pay-service/**`, `payment-handler-contract`, `internal-api-contract` | `copenhagen/pay-service:<commit-sha>` |
| Merchant Mock | `backend/mocks/ucp-merchant-mock/**`, 관련 계약 | `copenhagen/ucp-merchant-mock:<commit-sha>` |
| PG Mock | `backend/mocks/pg-mock/**`, 관련 계약 | `copenhagen/pg-mock:<commit-sha>` |

정확한 변경 경로: 각 `*.gitlab-ci.yml`의 `rules:changes`. Gradle 공통 설정, `test-support` 변경 시 관련 서비스 job 실행.

## GitLab 설정

설정 권한: Maintainer 또는 Owner.

| 위치 | 설정 |
| --- | --- |
| `Settings > Merge requests > Merge checks` | `Pipelines must succeed` 활성화 |
| `Settings > Repository > Branch rules` | `develop` protected branch 지정 |
| `Allowed to merge` | 팀 정책에 따른 역할 |
| `Allowed to push and merge` | `No one` 명시 |
| 선택 | Approval rule, CODEOWNERS approval |

설정 검증: lint 실패 MR에서 merge 버튼 차단 확인.

## 배포 변수

등록 위치: `Settings > CI/CD > Variables`

| 변수 | 타입 | 값 |
| --- | --- | --- |
| `DEV_DEPLOY_HOST` | Variable | 개발 EC2 public DNS 또는 IP |
| `DEV_DEPLOY_USER` | Variable | SSH 사용자 |
| `DEV_DEPLOY_PATH` | Variable | EC2 배포 디렉터리 절대 경로 |
| `DEV_SSH_PRIVATE_KEY` | File | 배포 사용자 SSH private key, 마지막 줄바꿈 포함 |
| `DEV_SSH_KNOWN_HOSTS` | File | 신뢰 환경에서 fingerprint를 확인한 SSH host key |
| `DEV_DEPLOY_WAIT_TIMEOUT` | Variable, 선택 | health 대기 시간, 기본 120초 |

- 보호 설정: protected variable 권장, `develop` protected branch 필요
- 환경 범위: `development`. 빌드/전송 job은 `prepare` 사용
- 비밀값: 레포와 Job 로그 기록 금지. SSH 키의 masked 설정 지원 여부 확인
- Registry 변수, Deploy Token 불필요

## 실행 환경

- Runner: Gradle 실행, privileged Docker-in-Docker, EC2 SSH 포트 접근
- Docker daemon 연결 오류: Runner 관리자에게 Docker-in-Docker 허용 여부 확인
- EC2: Bash, gunzip, SSH 사용자의 sudo 없는 Docker 실행 권한

## EC2 배포 과정

전송 파일: `docker-compose.yml`, `infra/nginx/nginx.conf`, `scripts/deploy-dev-service.sh`

1. 변경 서비스 이미지 빌드, commit SHA 태그 지정
2. SSH 압축 스트림으로 EC2 적재. 양쪽 `pipefail` 적용
3. 빌드/전송 성공 후 배포 시작, 적재 이미지 확인
4. 임시 `.env`에 대상 이미지 기록
5. `--no-deps --no-build --pull never --wait`로 대상 컨테이너 교체
6. health 확인, 실행 중인 Nginx 검사와 reload, `.env` 확정
7. 실패 시 진단 출력, 이전 이미지 복구와 Nginx reload 시도

동시 배포 제한: 모든 개발 배포 job이 `development-ec2` resource group 공유. DB와 Nginx 설정 파일 원복 제외.

### 배포 실패 진단

- 전달 경로: 기존 SSH stdout/stderr
- 실패 단계: `startup-health`, `nginx-status`, `nginx-check`, `nginx-reload`
- 롤백 전 출력: 서비스, 신규 이미지, 실패 단계와 코드, 대상 서비스와 Nginx 상태, ExitCode, OOMKilled, health, 최근 로그
- 로그 제한: 컨테이너별 최근 100줄, 진단 줄당 4096자
- 롤백 실패 시 추가 진단 수집
- 진단 수집 오류: 롤백 계속 진행
- 종료 코드: 롤백 결과와 관계없이 최초 배포 실패 코드 유지

| 롤백 결과 | 의미 |
| --- | --- |
| `ROLLBACK SUCCESS` | 이전 이미지 복구 성공 |
| `ROLLBACK FAILED` | 복구 실패. 단계와 종료 코드 표시 |
| `ROLLBACK UNAVAILABLE` | 이전 이미지 기록 또는 로컬 이미지 없음 |

```text
FAIL deploy service=pay-service image=example/pay:commit-sha step=startup-health exit=1
ROLLBACK attempting service=pay-service
ROLLBACK SUCCESS service=pay-service
```

마스킹 대상: `.env`의 `KEY=VALUE`와 실행 환경 중 이름에 `PASSWORD`, `SECRET`, `TOKEN`, `API_KEY`, `PRIVATE_KEY`, `CREDENTIAL`이 포함된 비어 있지 않은 값. 문자 그대로 치환 후 길이 제한. 앱에서 생성한 별도 민감정보는 필터 범위 제외. 앱 로그의 비밀값과 실제 결제 데이터 기록 금지.

## 최초 준비

1. [Docker와 개발 EC2 구성](../docker/README.md)에 따라 Docker, 배포 디렉터리 준비
2. `.env`, PostgreSQL 3개, Redis 준비
3. GitLab 필수 변수 5개와 선택 변수 1개 등록
4. EC2 Security Group에서 Runner SSH 접근 허용
5. MR pipeline의 필수 job 성공 확인
6. `develop` merge 후 SSH 이미지 적재와 배포 job 성공 확인
7. `docker compose ps`로 서비스 healthy 확인
8. 전체 앱 배포 후 Nginx 시작

## 참고

- [Pipeline 성공 필수 설정](https://docs.gitlab.com/user/project/merge_requests/auto_merge/#require-a-successful-pipeline-for-merge)
- [Protected branch와 MR](https://docs.gitlab.com/user/project/repository/branches/protected/#require-merge-requests)
- [CI/CD SSH 키](https://docs.gitlab.com/ci/jobs/ssh_keys/)
- [Job artifact](https://docs.gitlab.com/ci/jobs/job_artifacts/)
