# UCP 문서

UCP 계약, schema와 protocol 관련 해석을 기록하는 곳입니다.

계약을 추가하거나 변경할 때 request/response 의미, 필수·선택 필드, validation, 상태 전이, version과 호환성 정책을 함께 기록합니다. 기준 DTO와 schema 코드는 `backend/libs/ucp-contract/`에 두고 실제 구매 흐름 조정은 `core-service`에 구현합니다.

현재는 실행 기반만 있으며 업무용 UCP 계약은 구현되어 있지 않습니다.
