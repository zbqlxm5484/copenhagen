#!/bin/sh

set -eu

if [ "$#" -lt 3 ] || [ "$#" -gt 5 ]; then
    echo "Usage: $0 <compose-service> <image-variable> <image> [compose-file] [wait-timeout]" >&2
    exit 2
fi

compose_service=$1
image_variable=$2
container_image=$3
compose_file=${4:-docker-compose.yml}
wait_timeout=${5:-120}

case "$compose_service:$image_variable" in
    core-service:CORE_IMAGE|mcp-server:MCP_IMAGE|pay-service:PAY_IMAGE|ucp-merchant-mock:MERCHANT_MOCK_IMAGE|pg-mock:PG_MOCK_IMAGE)
        ;;
    *)
        echo "Unsupported service and image-variable pair: $compose_service:$image_variable" >&2
        exit 2
        ;;
esac

case "$wait_timeout" in
    ''|*[!0-9]*|0)
        echo "Wait timeout must be a positive integer." >&2
        exit 2
        ;;
esac

if [ ! -f "$compose_file" ]; then
    echo "Compose file not found: $compose_file" >&2
    exit 1
fi

env_file=.env
temporary_env="${env_file}.tmp.$$"
touch "$env_file"
chmod 600 "$env_file"
trap 'rm -f "$temporary_env"' 0 1 2 15

previous_image=$(awk -v key="$image_variable" '
    index($0, key "=") == 1 {
        print substr($0, length(key) + 2)
        exit
    }
' "$env_file")

docker image inspect "$container_image" >/dev/null

awk -v key="$image_variable" -v value="$container_image" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
        print key "=" value
        replaced = 1
        next
    }
    { print }
    END {
        if (!replaced) {
            print key "=" value
        }
    }
' "$env_file" > "$temporary_env"

chmod 600 "$temporary_env"

deploy_with_env() {
    docker compose --env-file "$1" --file "$compose_file" up \
        -d \
        --no-deps \
        --no-build \
        --pull never \
        --wait \
        --wait-timeout "$wait_timeout" \
        "$compose_service" || return 1

    running_nginx=$(docker compose --env-file "$1" --file "$compose_file" \
        ps --status running --quiet nginx) || return 1
    if [ -n "$running_nginx" ]; then
        # 컨테이너 교체로 바뀐 upstream IP를 다시 읽습니다. 최초 배포는 Nginx 없이도 가능합니다.
        docker compose --env-file "$1" --file "$compose_file" exec -T nginx nginx -t &&
            docker compose --env-file "$1" --file "$compose_file" exec -T nginx nginx -s reload
    fi
}

if ! deploy_with_env "$temporary_env"; then
    docker compose --env-file "$temporary_env" --file "$compose_file" \
        logs --no-color --tail 100 "$compose_service" >&2 || true
    if [ -n "$previous_image" ] && docker image inspect "$previous_image" >/dev/null 2>&1; then
        echo "Deployment failed; attempting to restore the previously recorded image." >&2
        deploy_with_env "$env_file" || true
    fi
    exit 1
fi

mv "$temporary_env" "$env_file"
docker compose --env-file "$env_file" --file "$compose_file" ps "$compose_service"
