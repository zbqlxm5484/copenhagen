# API 문서

공개 API와 내부 서비스 API 계약을 기록하는 곳입니다. 현재 첫날 저장소에는 Actuator health endpoint 외의 업무 API가 구현되어 있지 않습니다.

API를 추가하거나 변경할 때 문서에 다음 내용을 남깁니다.

- 공개 API인지 `/internal/**` 내부 API인지
- HTTP method와 경로
- 요청·응답 필드, validation, 오류 형식
- 인증·인가 조건
- 멱등성, timeout, retry 등 호출 규칙
- 호환성 영향과 version 전환 방법

DTO의 기준 코드는 해당 `backend/libs/*-contract` 모듈에 두고, 이 디렉터리에는 사람이 읽을 수 있는 의미와 사용 규칙을 기록합니다. 비밀번호, token, 실제 고객 데이터는 예시에 넣지 않습니다.
