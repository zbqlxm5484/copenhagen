# 공용 스크립트

## `bootstrap-dev-infra.sh`

- 용도: 개발 EC2의 PostgreSQL 3개, Redis 최초 기동
- 기본 파일: `docker-compose.yml`, `.env`
- 중단 조건: `.env`에 `CHANGE_ME` 잔존
- 제외: 애플리케이션, Nginx

```bash
./scripts/bootstrap-dev-infra.sh [compose-file] [env-file]
```

## `deploy-dev-service.sh`

- 용도: GitLab deploy job의 단일 서비스 갱신
- 입력: 허용된 서비스와 이미지 변수 조합, EC2에 적재된 이미지
- 절차: 컨테이너 교체, health 확인, 실행 중인 Nginx 검사와 reload, `.env` 확정
- 실패 처리: 롤백 전 진단 출력, 이전 이미지 복구 시도, 최초 실패 코드 유지
- 롤백 결과: `SUCCESS`, `FAILED`, `UNAVAILABLE`
- 로컬 개발: `docker compose up --build`

```bash
./scripts/deploy-dev-service.sh \
  <compose-service> <image-variable> <image> [compose-file] [wait-timeout]
```

## `deploy-smoke.sh`

- 범위: 빈 DB 기동, 고정 기준 버전에서 MR 또는 develop 후보 버전으로 갱신, 실패 이미지 복구, 임시 자원 정리
- 요구사항: Docker/BuildKit, Compose 2.24.4 이상, Bash, Git, jq
- 결과 경로: `tmp/deploy-smoke/`
- 개인 `.env`, 개발 EC2 접근 없음
- 상세: [MR 배포 스모크 테스트](../infra/gitlab/README.md#mr-배포-스모크-테스트)

```bash
git fetch origin develop
SMOKE_BASE_REF=origin/develop bash scripts/deploy-smoke.sh
```

## `pay-observability-smoke.sh`

- 범위: Pay DB·PG Mock·Pay만 격리 기동해 health, 인증 실패, `X-Request-Id` 응답·로그 연결과 합성 Authorization 값 비노출을 확인합니다.
- 기본 설정: 대사 Worker·알림 Worker·FCM 발송은 꺼서 외부 계정과 실제 결제를 사용하지 않습니다.
- 자원: 임시 Compose 프로젝트·네트워크·볼륨을 만들고 종료 시 함께 정리합니다.
- 실제 Firebase 수신·모바일 앱 로그·Nginx 연계는 이 스크립트의 범위가 아닙니다.

```bash
bash scripts/pay-observability-smoke.sh
```

## 실패 진단 검증

기준 SHA·CI 의존 관계 및 Docker CLI 대체 테스트. 요구사항: Python 3, PyYAML, Bash, Git, jq. Docker daemon, EC2 불필요. CI·스크립트 변경 시 `ci-config-test`에서도 실행합니다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
bash -n scripts/deploy-smoke.sh
sh -n scripts/smoke-base.sh
sh -n scripts/deploy-dev-service.sh
shellcheck scripts/smoke-base.sh scripts/deploy-smoke.sh scripts/deploy-dev-service.sh
git diff --check
```

| 검증 항목 | 2026-09-10 결과 |
| --- | --- |
| 회귀 테스트 10개와 하위 시나리오 | 통과: 진단 순서, 종료 코드, HTTP 오류, Merchant 인증 전환, Nginx 오류, 롤백, `.env`, 비밀값 마스킹 |
| 문법, ShellCheck 0.10.0, diff 검사 | 통과 |
| 격리 컨테이너 배포 | 통과: 롤백 전 진단, 합성 비밀번호 마스킹, 이전 이미지 healthy 복구, `.env` 유지, 종료 코드 `1` |
| 전체 앱 스모크, GitLab Runner, EC2 | 미검증 |

격리 검증 환경: Podman API, Docker Compose. 테스트 컨테이너, 네트워크, 전용 이미지 정리 완료.

## `smoke-base.sh`

- 용도: `deploy-smoke.sh`가 사용할 고정 기준 SHA 선택과 Git 이력 검증. stdout에는 SHA만 출력
- 일반 MR: `CI_MERGE_REQUEST_DIFF_BASE_SHA`, 병합 결과 MR: `CI_MERGE_REQUEST_TARGET_BRANCH_SHA`
- develop push: `CI_COMMIT_BEFORE_SHA`. 최초 push의 zero SHA는 실패 처리
- CI 후보: checkout `HEAD`가 `CI_COMMIT_SHA`와 일치해야 함
- 기준 누락·잘못된 SHA·없는 커밋·비조상 커밋: 실패. 이동하는 브랜치나 `HEAD^`로 대체하지 않음
- 로컬에서만 `SMOKE_BASE_REF` 사용 가능. CI에서는 override를 무시하고 pipeline 기준을 사용
- 기준 커밋 선택은 DB schema 원복 또는 실제 EC2 배포 버전 일치를 보장하지 않음

2026-09-17 검증: 로컬 회귀 테스트 24개 통과. 실제 Git 이력으로 동시 머지·Retry·다중 커밋 push와 잘못된 기준을 재현했고, 변경 경로별 필수 smoke 및 배포 의존 관계를 검사했습니다. Docker CLI 대체 테스트 결과이며 실제 GitLab·EC2 실행과 구분합니다.
