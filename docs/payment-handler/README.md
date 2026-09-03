# Payment Handler 문서

AP2와 유사한 권한 부여, mandate, credential, 결제 handler 계약을 기록하는 곳입니다.

계약을 추가하거나 변경할 때 actor, 요청·응답, 상태 전이, 오류, 멱등성, 보안 경계와 민감정보 처리 원칙을 함께 기록합니다. 기준 DTO와 schema 코드는 `backend/libs/payment-handler-contract/`에 두고 실제 처리 로직과 데이터는 `pay-service`가 소유합니다.

문서나 테스트 데이터에 실제 credential, 카드 정보, 비밀번호, token을 넣지 않습니다. 현재는 실행 기반만 있으며 업무용 결제 계약은 구현되어 있지 않습니다.
