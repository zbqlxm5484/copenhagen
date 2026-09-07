## 관련 이슈
- Jira: S15P21A207-123

## 작업 내용
- 결제 요청 API 추가
- PaymentRequest DTO 구현

## 주요 변경 사항
- `PaymentRequest`, `PaymentResponse` DTO 추가
- 중복 결제 방지를 위한 멱등성 키 검증 추가

## 테스트
- [ ] 로컬 실행 확인
- [ ] 정상 요청 테스트
- [ ] 예외 케이스 테스트
- [ ] 기존 기능 영향 확인

## 리뷰 포인트
- PaymentRequest DTO 필드 구성이 적절한지
- 결제 API의 책임 범위가 Core / Pay 설계와 일치하는지

## 기타
- 없음