# Nginx

외부 요청을 받는 유일한 reverse proxy입니다. 내부 서비스 호출은 Nginx를 거치지 않고 Docker DNS를 사용합니다.

| 외부 경로 | 전달 대상 |
| --- | --- |
| `/mcp` | `mcp-server` |
| `/api/core/**` | `core-service` |
| `/api/pay/**` | `pay-service` |
| `/.well-known/ucp` | `core-service` |
| `/merchant/v1/**` | `pay-service` |

`/internal`과 `/internal/**`는 명시적으로 차단합니다. 새 공개 경로를 추가할 때는 먼저 책임 서비스와 인증·인가 정책을 정하고, 내부 endpoint가 노출되지 않는지 확인합니다.
