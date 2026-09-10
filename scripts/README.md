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

- 범위: 빈 DB 기동, 기준 버전에서 MR 버전으로 갱신, 실패 이미지 복구, 임시 자원 정리
- 요구사항: Docker/BuildKit, Compose 2.24.4 이상, Bash, Git, jq
- 결과 경로: `tmp/deploy-smoke/`
- 개인 `.env`, 개발 EC2 접근 없음
- 상세: [MR 배포 스모크 테스트](../infra/gitlab/README.md#mr-배포-스모크-테스트)

```bash
git fetch origin develop
SMOKE_BASE_REF=origin/develop bash scripts/deploy-smoke.sh
```

## 실패 진단 검증

Docker CLI 대체 테스트. 요구사항: Python 3, Bash, Git, jq. Docker daemon, EC2 불필요.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
bash -n scripts/deploy-smoke.sh
sh -n scripts/deploy-dev-service.sh
shellcheck scripts/deploy-smoke.sh scripts/deploy-dev-service.sh
git diff --check
```

| 검증 항목 | 2026-09-10 결과 |
| --- | --- |
| 회귀 테스트 9개와 하위 시나리오 | 통과: 진단 순서, 종료 코드, HTTP 오류, Nginx 오류, 롤백, `.env`, 비밀값 마스킹 |
| 문법, ShellCheck 0.10.0, diff 검사 | 통과 |
| 격리 컨테이너 배포 | 통과: 롤백 전 진단, 합성 비밀번호 마스킹, 이전 이미지 healthy 복구, `.env` 유지, 종료 코드 `1` |
| 전체 앱 스모크, GitLab Runner, EC2 | 미검증 |

격리 검증 환경: Podman API, Docker Compose. 테스트 컨테이너, 네트워크, 전용 이미지 정리 완료.
