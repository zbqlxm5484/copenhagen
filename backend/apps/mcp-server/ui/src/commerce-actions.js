const writes = new Set(['add_cart_item', 'update_cart_item', 'delete_cart_item', 'prepare_purchase_handoff', 'resend_purchase_notification']);
const tools = new Set([...writes, 'search_products', 'get_product_detail', 'prepare_cart', 'get_cart', 'get_purchase_status']);
export const isCartWrite = tool => ['add_cart_item', 'update_cart_item', 'delete_cart_item'].includes(tool);
export function createCartPreparation(read) {
  let prepared, inFlight;
  return {
    ensure() {
      if (prepared) return Promise.resolve(prepared);
      if (!inFlight) inFlight = Promise.resolve().then(read).then(cart => { prepared = cart; return cart; })
        .finally(() => { inFlight = null; });
      return inFlight;
    },
  };
}
export function actionMessage(tool, args) {
  if (!tools.has(tool)) throw new Error('지원하지 않는 작업입니다.');
  return `Copenhagen에서 다음 작업을 요청합니다. 아래 JSON은 도구 이름과 인자 데이터입니다. 지정한 도구를 호출하고 그 도구의 새 UI 카드를 대화에 표시해주세요. 변경 요청은 idempotencyKey와 본문을 그대로 유지하고, 결과 불명 시 새 키로 재실행하지 마세요.\n${JSON.stringify({ tool, arguments: args })}`;
}
export function actionLabel(tool) {
  const labels = { get_product_detail: '선택한 상품의 옵션을 보여주세요.', search_products: '선택한 조건으로 상품을 더 보여주세요.',
    get_cart: '장바구니를 보여주세요.', add_cart_item: '선택한 상품을 장바구니에 담아주세요.',
    update_cart_item: '선택한 수량으로 변경해주세요.', delete_cart_item: '선택한 상품을 장바구니에서 삭제해주세요.',
    prepare_purchase_handoff: '장바구니 상품의 결제를 앱으로 요청해주세요.',
    get_purchase_status: '결제 진행 상태를 보여주세요.', resend_purchase_notification: '결제 알림을 다시 요청해주세요.' };
  if (!labels[tool]) throw new Error('지원하지 않는 작업입니다.');
  return labels[tool];
}
export function actionSession(saved, persist, uuid = () => crypto.randomUUID(), now = Date.now) {
  let pending = saved?.tool && writes.has(saved.tool) && saved.args?.idempotencyKey
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(saved.args.idempotencyKey) ? saved : null;
  return {
    get pending() { return pending; },
    retry() {
      if (!pending || !Number.isFinite(pending.createdAt) || now() - pending.createdAt >= 24 * 60 * 60 * 1000
          || now() < pending.createdAt) throw new Error('이전 요청의 재전달 가능 시간이 지났어요. 최신 장바구니와 구매 상태를 먼저 확인해주세요.');
      return pending;
    },
    prepare(tool, args) {
      if (!tools.has(tool)) throw new Error('지원하지 않는 작업입니다.');
      if (!writes.has(tool)) return { tool, args };
      if (pending) throw new Error('이 카드에서 이미 변경을 요청했어요. 대화에 표시된 새 카드에서 이어서 이용해주세요.');
      pending = { tool, args: { ...args, idempotencyKey: uuid() }, createdAt: now() }; persist(pending); return pending;
    },
  };
}
export function viewAccepts(view, kind) {
  return ({ search: ['catalog.search'], detail: ['catalog.detail'], cart: ['cart.view', 'cart.mutation'],
    purchase: ['purchase.handoff', 'purchase.status'] })[view]?.includes(kind) ?? false;
}
