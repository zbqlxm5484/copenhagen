# UCP Merchant Mock

로컬 및 개발 환경에서 외부 UCP Business 역할을 대신하는 시뮬레이터입니다.

이 모듈의 설계 기준 문서는 [DESIGN.md](DESIGN.md)입니다. 아키텍처, 불변 조건, 상태 전이, 서명 알고리즘, 데이터 모델과 그 근거를 담고 있습니다. 기능을 추가하거나 변경할 때는 먼저 DESIGN.md를 갱신하고 리뷰한 뒤 구현합니다. DB 구조는 [schema.dbml](schema.dbml)과 [erd.png](erd.png)를 참고하세요.

- `merchant-db`를 단독으로 소유합니다.
- 결제가 필요할 때 `pay-service`를 내부 HTTP로 직접 호출합니다.
- 실제 Merchant 또는 운영 환경에서 사용하지 않습니다.
- Merchant 시나리오 재현에 필요한 최소 계약과 동작만 구현합니다.
- Flyway migration은 이 모듈의 `src/main/resources/db/migration/`에 둡니다.

`backend/`에서 다음과 같이 검증합니다.

```bash
./gradlew :mocks:ucp-merchant-mock:test
./gradlew :mocks:ucp-merchant-mock:build
```
