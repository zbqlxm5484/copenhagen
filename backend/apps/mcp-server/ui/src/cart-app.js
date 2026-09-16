import { cartView, currencyAmount, mutationSession } from './cart.js';
import { detailData, money, safeImage } from './catalog.js';

export function createCartUi(callTool, element, save) {
  const $ = id => document.getElementById(id);
  const enabled = document.body.dataset.cartEnabled === 'true';
  let current, loading = false, writing = false, generation = 0;
  const session = mutationSession(window.openai?.widgetState?.cartPending, () => { save(); renderPending(); });
  const note = message => {
    $('cart-status').textContent = message; $('cart-message').textContent = message;
    const detailNote = $('cart-detail-note'); if (detailNote) detailNote.textContent = message;
  };
  function renderPending() {
    $('cart-retry').hidden = !session.pending;
    $('cart-retry').disabled = writing;
    $('cart-dialog-retry').hidden = !session.pending;
    $('cart-dialog-retry').disabled = writing;
  }
  function remember(data) {
    if (!current || current.cart.cartId !== data.cart.cartId || data.cart.version >= current.cart.version) current = data;
    $('cart-open').textContent = `장바구니 (${current.cart.lines.reduce((n, line) => n + line.quantity, 0)})`;
  }
  async function refresh(open = false) {
    if (!enabled || loading) return;
    const id = ++generation; loading = true;
    if (open) {
      $('comparison').close(); $('cart-dialog').showModal();
      $('cart-summary').replaceChildren();
      $('cart-body').replaceChildren(element('p', 'Core 장바구니를 조회하고 있습니다.'));
    }
    try {
      const data = cartView(await callTool('get_cart', {}));
      if (id !== generation) return;
      remember(data);
      if (session.pending) note('이전 요청의 결과가 미확정입니다. 같은 요청으로 확인해주세요.');
      if (open) render();
      return current;
    } catch (error) {
      note('장바구니를 불러오지 못했습니다. 다시 열어 확인해주세요.');
      if (open) $('cart-body').replaceChildren(element('p', '장바구니를 불러오지 못했습니다.'), retryRead());
      throw error;
    } finally { loading = false; renderPending(); }
  }
  function retryRead() {
    const retry = element('button', '다시 조회'); retry.onclick = () => void refresh(true).catch(() => {}); return retry;
  }
  function render() {
    const body = $('cart-body'); body.replaceChildren();
    if (!current.cart.lines.length) body.append(element('p', '장바구니가 비어 있어요.', 'empty'));
    for (const group of current.groups) {
      const lines = current.cart.lines.filter(x => x.merchantId === group.merchantId && x.currency === group.currency);
      const heading = element('div', undefined, 'cart-group-heading');
      heading.append(element('h3', `${lines[0]?.merchantName ?? group.merchantId}${group.currency === 'KRW' ? '' : ` · ${group.currency}`}`),
        element('span', group.status === 'FAILED' ? '견적 조회 실패' : currencyAmount(group.itemSubtotal, group.currency), 'sub'));
      body.append(heading);
      for (const line of lines) {
        const row = element('article', undefined, 'cart-line'); const img = element('img');
        const image = safeImage(line.imageUrl);
        if (image) { img.src = image; img.alt = ''; img.referrerPolicy = 'no-referrer'; img.loading = 'lazy'; row.append(img); }
        const content = element('div');
        const name = element('p', line.name, 'cart-name'); name.title = line.name;
        const option = element('p', `${line.optionLabel || '옵션 없음'} · ${line.quantity}개`, 'sub cart-option');
        option.title = option.textContent;
        content.append(name, option);
        const edit = element('button', '변경', 'secondary');
        edit.setAttribute('aria-label', `${line.name} 수량 · 옵션 변경`);
        const remove = element('button', '삭제', 'secondary');
        remove.setAttribute('aria-label', `${line.name} 삭제`);
        const captured = current.cart;
        edit.onclick = () => void editLine(line, captured);
        remove.onclick = () => {
          if (writing || session.pending) { note('이전 변경 결과를 먼저 확인해주세요.'); return; }
          remove.textContent = '삭제 확인';
          remove.setAttribute('aria-label', `${line.name} 삭제 확인`);
          remove.onclick = () => void beginEdit('delete_cart_item', captured, line, {});
        };
        const actions = element('div', undefined, 'line-actions');
        actions.append(element('span', currencyAmount(line.lineTotal, line.currency), 'price'), edit, remove);
        content.append(actions);
        row.append(content); body.append(row);
      }
      if (group.status !== 'FAILED' && (group.discount !== null || group.shipping !== null)) {
        body.append(element('p', `할인 ${currencyAmount(group.discount, group.currency)} · 배송비 ${currencyAmount(group.shipping, group.currency)}`, 'sub'));
      }
    }
    const summary = $('cart-summary'); summary.replaceChildren();
    for (const total of current.totals) {
      const row = element('div', undefined, 'cart-total');
      const label = total.total === null ? '상품 합계' : '예상 결제금액';
      row.append(element('span', `${label}${total.currency === 'KRW' ? '' : ` · ${total.currency}`}`),
        element('strong', currencyAmount(total.total ?? total.itemSubtotal, total.currency)));
      summary.append(row);
    }
    if (current.cart.lines.length) {
      const unknown = current.totals.some(x => x.discount === null || x.shipping === null);
      summary.append(element('p', unknown ? '할인·배송비 별도 확인 · 주문 전 최종 금액 재확인' : '주문 전 가격·재고와 최종 금액을 다시 확인해요.', 'sub'));
    }
    if (current.mode === 'QA') {
      const rules = element('details', undefined, 'qa-pricing');
      rules.append(element('summary', 'QA 예상가 · 계산 기준'),
        element('p', '판매처별 상품금액 5% 할인(100원 미만 절사). 할인 후 3만 원 이상 무료배송, 그 미만 3,000원. 실제 결제 없음.', 'muted'));
      summary.append(rules);
    }
    $('cart-confirm').textContent = '새로고침';
    $('cart-confirm').onclick = () => void refresh(true).catch(() => {});
  }
  const reasons = { OPTION_REQUIRED: '옵션을 선택해주세요.', INVALID_OPTION: '옵션을 다시 선택해주세요.',
    UNAVAILABLE: '현재 담을 수 없는 상품입니다.', INSUFFICIENT_STOCK: '요청한 수량만큼 재고가 없습니다.',
    NOT_FOUND: '상품 또는 항목을 찾을 수 없습니다.', VERSION_CONFLICT: '다른 곳에서 장바구니가 변경됐습니다. 다시 확인해주세요.',
    KEY_CONFLICT: '이전 요청과 내용이 다릅니다. 장바구니를 확인해주세요.', LIMIT_EXCEEDED: '장바구니 허용 범위를 초과했습니다.',
    OUTCOME_UNKNOWN: '처리 결과가 미확정입니다. 같은 요청으로 확인해주세요.',
    DUPLICATE_SELECTION: '같은 옵션이 이미 장바구니에 있습니다. 해당 항목의 수량을 변경해주세요.' };
  async function beginEdit(tool, cart, line, fields) {
    if (writing || session.pending) { note('이전 변경 결과를 먼저 확인해주세요.'); return; }
    try {
      session.begin(tool, { cartId: cart.cartId, lineId: line.lineId, expectedVersion: cart.version,
        idempotencyKey: crypto.randomUUID(), ...fields });
      await runPending();
    } catch (error) { note(error.message); }
  }
  async function editLine(line, cart) {
    if (writing || session.pending) { note('이전 변경 결과를 먼저 확인해주세요.'); return; }
    const id = ++generation; const body = $('cart-body');
    body.replaceChildren(element('p', '변경 가능한 옵션을 확인하고 있습니다.'));
    try {
      const request = { productId: line.productId, merchantId: line.merchantId };
      if (line.optionId) request.optionId = line.optionId;
      const data = detailData(await callTool('get_product_detail', request), request);
      if (id !== generation || !$('cart-dialog').open) return;
      if (data.status === 'NOT_FOUND') throw new Error('현재 상품을 찾을 수 없습니다.');
      const product = data.product;
      body.replaceChildren(element('p', line.name));
      const quantity = element('input'); quantity.type = 'number'; quantity.min = '1'; quantity.max = '99'; quantity.step = '1'; quantity.value = line.quantity;
      const qtyLabel = element('label', '변경할 수량'); qtyLabel.append(quantity);
      const options = element('select');
      options.add(new Option(product.optionsRequired ? '옵션을 선택해주세요' : '옵션 없음', ''));
      for (const option of product.variants) {
        const item = new Option(`${option.label} · ${money(option.price)}`, option.optionId);
        item.disabled = ['OUT_OF_STOCK', 'DISCONTINUED'].includes(option.availability) || option.purchaseAllowed === false;
        options.add(item);
      }
      options.value = line.optionId;
      const optionLabel = element('label', '변경할 옵션'); optionLabel.append(options);
      const submit = element('button', '변경 저장');
      submit.onclick = async () => {
        const count = Number(quantity.value);
        if (!Number.isInteger(count) || count < 1 || count > 99 || (product.optionsRequired && !options.value)) {
          note('필수 옵션과 1~99 사이의 수량을 확인해주세요.'); return;
        }
        submit.disabled = true;
        await beginEdit('update_cart_item', cart, line, { quantity: count, optionId: options.value });
        submit.disabled = false;
      };
      body.append(qtyLabel, optionLabel, submit);
    } catch (error) {
      if (id === generation) body.replaceChildren(element('p', error.message), retryRead());
    }
  }
  async function runPending() {
    if (writing) return;
    writing = true; renderPending(); note('장바구니에 반영하고 있습니다.');
    try {
      const data = await session.run(callTool);
      if (data.status === 'APPLIED') {
        if (data.action === 'ADD') {
          note('장바구니에 담았어요.'); $('comparison').close(); $('cart-dialog').showModal();
          $('cart-summary').replaceChildren();
          $('cart-body').replaceChildren(element('p', '장바구니에 담았어요.'));
          $('cart-confirm').textContent = '장바구니 확인';
          $('cart-confirm').onclick = () => void refresh(true).catch(() => {});
          void refresh().catch(() => {});
        } else {
          await refresh(true).catch(() => {});
          note(data.action === 'DELETE' ? '항목을 삭제했어요.' : '장바구니를 변경했어요.');
        }
      } else {
        if (data.status !== 'UNKNOWN') await refresh(true).catch(() => {});
        note(reasons[data.reason] ?? '장바구니 상태를 다시 확인해주세요.');
      }
    } catch {
      note('변경 결과를 확인하지 못했습니다. 중복 반영을 막기 위해 같은 요청으로 다시 확인해주세요.');
    } finally { writing = false; renderPending(); }
  }
  function appendAdd(body, product, request) {
    if (!enabled) return;
    const quantity = element('input'); quantity.type = 'number'; quantity.min = '1'; quantity.max = '99'; quantity.step = '1'; quantity.value = '1';
    const label = element('label', '담을 수량'); label.append(quantity);
    const button = element('button', '장바구니 담기');
    const detailNote = element('p', undefined, 'sub'); detailNote.id = 'cart-detail-note'; detailNote.setAttribute('role', 'status');
    const selected = product.variants.find(x => x.optionId === product.selectedOptionId);
    button.disabled = ['REQUIRED', 'INVALID'].includes(product.selectionStatus)
      || ['OUT_OF_STOCK', 'DISCONTINUED'].includes(product.offer.availability) || selected?.purchaseAllowed === false;
    button.onclick = async () => {
      if (writing || loading || session.pending) { note('이전 요청의 결과를 먼저 확인해주세요.'); renderPending(); return; }
      const count = Number(quantity.value);
      if (!Number.isInteger(count) || count < 1 || count > 99) { note('수량은 1~99 사이의 정수로 입력해주세요.'); return; }
      button.disabled = true;
      try {
        const view = await refresh();
        if (!view) return;
        session.begin('add_cart_item', { cartId: view.cart.cartId, expectedVersion: view.cart.version,
          productId: request.productId, merchantId: request.merchantId, optionId: product.selectedOptionId ?? '',
          quantity: count, idempotencyKey: crypto.randomUUID() });
        await runPending();
      } catch (error) { note(error.message); } finally { button.disabled = false; }
    };
    body.append(label, button, detailNote);
  }
  if (enabled) {
    $('cart-bar').hidden = false;
    $('cart-open').onclick = () => void refresh(true).catch(() => {});
    $('cart-retry').onclick = () => void runPending();
    $('cart-dialog-retry').onclick = () => void runPending();
    $('cart-close').onclick = $('cart-continue').onclick = () => $('cart-dialog').close();
    $('cart-dialog').addEventListener('close', () => { ++generation; });
    renderPending();
  }
  return { enabled, appendAdd, refresh, pending: () => session.pending };
}
