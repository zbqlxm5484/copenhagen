import test from 'node:test';
import assert from 'node:assert/strict';
import { amount, payload, searchArguments, variantLabel, purchaseLink, pendingRequest, restorablePending } from './live-state.js';

test('전체 상품군은 categoryId를 생략하고 미확정 금액을 0으로 만들지 않는다', () => {
  assert.deepEqual(searchArguments({ query: '삼성', categoryId: '', minPrice: '', maxPrice: '' }), { pageSize: 4, query: '삼성' });
  assert.equal(amount(null), '미확정'); assert.equal(amount(0), '0원');
});
test('Core가 반환한 variant 식별자와 옵션 이름을 사용한다', () => {
  assert.equal(variantLabel({ optionGroups: [{ values: [{ id: 'blue', name: '블루' }, { id: '250', name: '250mm' }] }] },
    { optionValueIds: ['blue', '250'] }), '블루 · 250mm');
});
test('구매 링크는 정확한 허용 경로와 만료를 검사한다', () => {
  const receipt = { purchaseRequestId: 'prq_1', appLink: 'https://app.example/purchase/prq_1', expiresAt: '2030-01-01T00:00:00Z' };
  assert.equal(purchaseLink(receipt, 'https://app.example/purchase/', 0), receipt.appLink);
  assert.throws(() => purchaseLink({ ...receipt, appLink: `${receipt.appLink}?token=secret` }, 'https://app.example/purchase/', 0));
  assert.throws(() => purchaseLink(receipt, 'https://app.example/purchase/', Date.parse('2031-01-01')));
});
test('복구 요청의 UUID 키와 본문을 보존한다', () => {
  const pending = pendingRequest('update_cart_item', { cartVersion: 12, cartItemId: 'i1', merchantVariantId: 'v1', quantity: 2 });
  assert.deepEqual(restorablePending(JSON.parse(JSON.stringify(pending))), pending);
  assert.match(pending.args.idempotencyKey, /^[0-9a-f-]{36}$/);
});
test('성공 응답과 결과 불명 오류를 구분한다', () => {
  assert.equal(payload({ kind: 'cart.mutation', data: { outcome: 'APPLIED', cart: null } }, 'cart.mutation').outcome, 'APPLIED');
  assert.throws(() => payload({ isError: true, structuredContent: { error: { outcome: 'UNKNOWN', message: '결과 확인 필요' } } }, 'cart.mutation'),
    error => error.outcome === 'UNKNOWN');
});
