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

docker compose --env-file "$temporary_env" --file "$compose_file" pull "$compose_service"

if ! docker compose --env-file "$temporary_env" --file "$compose_file" up \
    -d \
    --no-deps \
    --no-build \
    --wait \
    --wait-timeout "$wait_timeout" \
    "$compose_service"; then
    if grep -q "^${image_variable}=" "$env_file"; then
        echo "Deployment failed; attempting to restore the previously recorded image." >&2
        docker compose --env-file "$env_file" --file "$compose_file" pull "$compose_service" || true
        docker compose --env-file "$env_file" --file "$compose_file" up \
            -d \
            --no-deps \
            --no-build \
            --wait \
            --wait-timeout "$wait_timeout" \
            "$compose_service" || true
    fi
    exit 1
fi

mv "$temporary_env" "$env_file"
docker compose --env-file "$env_file" --file "$compose_file" ps "$compose_service"
