#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d -t copenhagen-smoke-XXXXXXXX)
export COMPOSE_PROJECT_NAME
COMPOSE_PROJECT_NAME=$(basename "$work" | tr '[:upper:]' '[:lower:]')
results=${SMOKE_ARTIFACT_DIR:-$root/tmp/deploy-smoke}
mkdir -p "$results"
results=$(cd "$results" && pwd)
: > "$results/result.log"
wait_timeout=${SMOKE_WAIT_TIMEOUT:-180}
curl_image=docker.io/curlimages/curl:8.16.0
services=(pg-mock pay-service ucp-merchant-mock core-service mcp-server)
image_variables=(PG_MOCK_IMAGE PAY_IMAGE MERCHANT_MOCK_IMAGE CORE_IMAGE MCP_IMAGE)
images=()
phase=prepare

# 개인 .env와 기존 배포 이미지 설정을 사용하지 않습니다.
unset PG_MOCK_IMAGE PAY_IMAGE MERCHANT_MOCK_IMAGE CORE_IMAGE MCP_IMAGE SMOKE_NGINX_IMAGE
unset CORE_DB_USER CORE_DB_PASSWORD PAY_DB_USER PAY_DB_PASSWORD MERCHANT_DB_USER MERCHANT_DB_PASSWORD
password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
printf 'CORE_DB_PASSWORD=%s\nPAY_DB_PASSWORD=%s\nMERCHANT_DB_PASSWORD=%s\n' \
    "$password" "$password" "$password" > "$work/settings.env"
chmod 600 "$work/settings.env"

compose() {
    docker compose --env-file "$work/.env" --file "$work/compose.yml" "$@"
}

record() {
    printf '%s\n' "$*" | tee -a "$results/result.log"
}

snapshot() {
    if [ -f "$work/compose.yml" ]; then
        compose ps --all > "$results/$phase-containers.txt" 2>&1 || true
        compose logs --no-color --tail 200 2>&1 \
            | sed "s/$password/[redacted]/g" > "$results/$phase-containers.log" || true
        : > "$results/$phase-health.log"
        for container in $(compose ps --all --quiet); do
            docker inspect --format '{{.Name}} {{json .State}}' "$container" \
                | sed "s/$password/[redacted]/g" >> "$results/$phase-health.log" || true
        done
    fi
}

cleanup() {
    local status=$?
    trap - EXIT
    snapshot
    if [ -f "$work/compose.yml" ]; then
        compose down --volumes --remove-orphans || status=1
    fi
    if [ "${#images[@]}" -gt 0 ]; then
        docker image rm "${images[@]}" >/dev/null 2>&1 || true
    fi
    rm -rf "$work"
    record "exit=$status phase=$phase"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

build_images() {
    local source=$1 tag=$2 service dockerfile image
    phase="build-$tag"
    for service in "${services[@]}"; do
        case "$service" in
            pg-mock|ucp-merchant-mock) dockerfile="mocks/$service/Dockerfile" ;;
            *) dockerfile="apps/$service/Dockerfile" ;;
        esac
        image="$COMPOSE_PROJECT_NAME/$service:$tag"
        images+=("$image")
        docker build --load --file "$source/backend/$dockerfile" --tag "$image" "$source/backend"
    done
    images+=("$COMPOSE_PROJECT_NAME/nginx:$tag")
    # 실제 Compose와 동일한 Nginx image 및 설정을 사용합니다.
    docker build --load --tag "$COMPOSE_PROJECT_NAME/nginx:$tag" --file - "$source/infra/nginx" <<EOF
FROM $(docker compose --env-file "$work/settings.env" --file "$source/docker-compose.yml" config --format json | jq -er '.services.nginx.image')
COPY nginx.conf /etc/nginx/nginx.conf
EOF
}

configure() {
    local source=$1 tag=$2 index
    cp "$work/settings.env" "$work/.env"
    for index in "${!services[@]}"; do
        printf '%s=%s/%s:%s\n' "${image_variables[$index]}" \
            "$COMPOSE_PROJECT_NAME" "${services[$index]}" "$tag" >> "$work/.env"
    done
    printf 'SMOKE_NGINX_IMAGE=%s/nginx:%s\n' "$COMPOSE_PROJECT_NAME" "$tag" >> "$work/.env"
    render_compose "$source"
}

render_compose() {
    local source=$1
    docker compose --env-file "$work/.env" \
        --file "$source/docker-compose.yml" --file "$root/infra/docker/smoke.override.yml" \
        config --no-interpolate > "$work/compose.yml"
}

expect_http() {
    local url=$1 expected=$2 assertion=${3:-} response code body
    response=$(docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" "$curl_image" \
        --silent --show-error --connect-timeout 5 --max-time 10 \
        --header 'Accept: application/json' --write-out '\n%{http_code}' "$url")
    code=${response##*$'\n'}
    body=${response%$'\n'*}
    if [ "$code" != "$expected" ]; then
        record "FAIL $url expected=$expected actual=$code"
        return 1
    fi
    if [ -n "$assertion" ]; then
        printf '%s' "$body" | jq -e "$assertion" >/dev/null
    fi
    record "PASS $url status=$code"
}

check_http() {
    local service path source variable
    for service in "${services[@]}"; do
        expect_http "http://$service:8080/actuator/health" 200 '.status == "UP"'
    done
    compose exec -T nginx nginx -t
    # 아직 업무 API가 없는 Core/MCP도 upstream에 도달한 JSON 404인지 확인합니다.
    # Nginx 자체 404나 502를 정상 응답으로 인정하지 않습니다.
    for path in /api/core/__smoke__ /api/pay/__smoke__ /mcp/__smoke__ /merchant/v1/__smoke__; do
        expect_http "http://nginx$path" 404 ".status == 404 and .path == \"$path\""
    done
    expect_http http://nginx/internal/v1/__smoke__ 404
    for source in core-service mcp-server pay-service ucp-merchant-mock; do
        case "$source" in
            core-service|ucp-merchant-mock) variable=PAY_SERVICE_URL ;;
            mcp-server) variable=CORE_SERVICE_URL ;;
            pay-service) variable=PG_SERVICE_URL ;;
        esac
        # 환경 변수는 실행 중인 컨테이너 안에서 확장합니다.
        # shellcheck disable=SC2016
        compose exec -T "$source" sh -c 'wget -T 10 -qO- "$(printenv "$1")/actuator/health"' sh "$variable" \
            | jq -e '.status == "UP"' >/dev/null
        record "PASS $source -> $variable"
    done
    # shellcheck disable=SC2016
    compose exec -T core-service sh -c 'wget -T 10 -qO- "$MERCHANT_SERVICE_URL/.well-known/ucp"' \
        | jq -e '.ucp.services["dev.ucp.shopping"][0].transport == "rest" and (.keys | length > 0)' >/dev/null
    record 'PASS Core -> Merchant Profile discovery'
}

database_sql() {
    # shellcheck disable=SC2016
    compose exec -T "$1" sh -c \
        'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}

check_data() {
    local database marker
    for database in core-db pay-db merchant-db; do
        marker=$(printf 'SELECT id FROM deploy_smoke.marker;\n' | database_sql "$database")
        if [ "$marker" != "$COMPOSE_PROJECT_NAME" ]; then
            record "FAIL $database persisted data"
            return 1
        fi
        record "PASS $database persisted data"
    done
}

base_sha=$(git -C "$root" rev-parse "${SMOKE_BASE_REF:-origin/develop}^{commit}")
record "base=$base_sha candidate=$(git -C "$root" rev-parse HEAD) project=$COMPOSE_PROJECT_NAME"
if ! git -C "$root" merge-base --is-ancestor "$base_sha" HEAD; then
    record 'FAIL target branch is not included; rebase onto the target branch and rerun'
    exit 1
fi
docker info >/dev/null
docker pull "$curl_image"
build_images "$root" candidate
configure "$root" candidate
phase=fresh-deploy
compose up -d --no-build --wait --wait-timeout "$wait_timeout"
check_http
record 'PASS fresh deployment'
snapshot
compose down --volumes --remove-orphans

mkdir "$work/base"
git -C "$root" archive "$base_sha" | tar -x -C "$work/base"
build_images "$work/base" base
configure "$work/base" base
phase=baseline-deploy
compose up -d --no-build --wait --wait-timeout "$wait_timeout"
check_http
for database in core-db pay-db merchant-db; do
    database_sql "$database" <<SQL
CREATE SCHEMA deploy_smoke;
CREATE TABLE deploy_smoke.marker (id text PRIMARY KEY);
INSERT INTO deploy_smoke.marker VALUES ('$COMPOSE_PROJECT_NAME');
SQL
done
snapshot

# 기준 image의 .env와 DB를 유지하며 실제 배포처럼 서비스별로 갱신합니다.
render_compose "$root"
for index in "${!services[@]}"; do
    phase="upgrade-${services[$index]}"
    (cd "$work" && sh "$root/scripts/deploy-dev-service.sh" \
        "${services[$index]}" "${image_variables[$index]}" \
        "$COMPOSE_PROJECT_NAME/${services[$index]}:candidate" "$work/compose.yml" "$wait_timeout")
    check_http
    check_data
done
sed -i "s|^SMOKE_NGINX_IMAGE=.*|SMOKE_NGINX_IMAGE=$COMPOSE_PROJECT_NAME/nginx:candidate|" "$work/.env"
compose up -d --no-deps --no-build --pull never --wait --wait-timeout "$wait_timeout" nginx
phase=upgrade-complete
check_http
snapshot
record 'PASS upgrade deployment'

phase=rollback
images+=("$COMPOSE_PROJECT_NAME/core-service:broken")
docker build --load --tag "$COMPOSE_PROJECT_NAME/core-service:broken" - <<EOF
FROM docker.io/library/alpine:3.22
ENTRYPOINT ["/bin/sh", "-c", "exit 1"]
EOF
cp "$work/.env" "$work/previous.env"
if (cd "$work" && sh "$root/scripts/deploy-dev-service.sh" \
        core-service CORE_IMAGE "$COMPOSE_PROJECT_NAME/core-service:broken" "$work/compose.yml" 45); then
    record 'FAIL broken image deployment was accepted'
    exit 1
fi
cmp "$work/previous.env" "$work/.env"
restored=$(docker inspect --format '{{.Image}}' "$(compose ps --quiet core-service)")
[ "$restored" = "$(docker image inspect --format '{{.Id}}' "$COMPOSE_PROJECT_NAME/core-service:candidate")" ]
check_http
check_data
record 'PASS failed deployment restored the previous image and environment'
