import { App } from '@modelcontextprotocol/ext-apps';
import { productImage } from './product-image.js';
import { amount, payload, variantLabel } from './live-state.js';
import { availability } from './catalog.js';
import { purchaseProgress } from './purchase-progress.js';
import { createHostSizing } from './host-sizing.js';
import { actionMessage, actionSession, createCartPreparation, isCartWrite, viewAccepts } from './commerce-actions.js';
import { createReadCoalescer, liveTimedCall, requestTimeout } from './live-requests.js';
import { measuredCall, requestDiagnostics } from './request-diagnostics.js';

const $ = id => document.getElementById(id);
const el = (tag, text, cls) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; };
const view = document.body.dataset.view;
let app, ready = false, sending = false, internalReads = 0, latest, latestInput = window.openai?.toolInput ?? {};
let selection = window.openai?.widgetState?.commerceSelection;
function saveSelection() { window.openai?.setWidgetState?.({ version: 5, commercePending: session.pending, commerceSelection: selection }); }
const session = actionSession(window.openai?.widgetState?.commercePending,
  pending => window.openai?.setWidgetState?.({ version: 5, commercePending: pending, commerceSelection: selection }));
const coalesce = createReadCoalescer();
const cartPreparation = createCartPreparation(() => read('prepare_cart', {}, 'cart.view'));
window.copenhagenDiagnostics = requestDiagnostics;
const status = text => { $('status').textContent = text; sizing.notify(); };
const button = (label, fn, secondary = false) => { const n = el('button', label, secondary ? 'secondary' : ''); n.onclick = async () => {
  if (sending) return; n.disabled = true;
  try { await fn(); } catch (e) { status(e.authRequired ? '연결 또는 권한 동의를 완료한 뒤 다시 눌러주세요. 선택한 옵션과 수량은 유지됩니다.' : e.message); recovery(); } finally { n.disabled = false; }
}; return n; };
const sizing = createHostSizing({ getApp: () => ready ? app : null,
  getDisplayState: () => ({ mode: 'inline', confirmed: true }),
  measure: () => ({ width: innerWidth, height: document.querySelector('main').getBoundingClientRect().height }) });
new ResizeObserver(() => sizing.notify()).observe(document.querySelector('main'));
async function read(name, args, kind) {
  internalReads++;
  try {
    const result = await coalesce(name, args, () => measuredCall(name, () => liveTimedCall(() => ready
      ? app.callServerTool({ name, arguments: args }, { timeout: requestTimeout(name) + 1000 })
      : window.openai?.callTool ? window.openai.callTool(name, args) : Promise.reject(new Error('연결을 기다려주세요.')), name)));
    return payload(result, kind);
  } finally { internalReads--; }
}
async function deliver(intent) {
  if (sending) return;
  sending = true; status('새 결과 카드를 요청하고 있어요.');
  const prompt = actionMessage(intent.tool, intent.args);
  try {
    let result;
    if (ready) result = await app.sendMessage({ role: 'user', content: [{ type: 'text', text: prompt }] });
    else if (typeof window.openai?.sendFollowUpMessage === 'function') result = await window.openai.sendFollowUpMessage({ prompt });
    else {
      const text = el('textarea', null, 'action-fallback'); text.readOnly = true; text.value = prompt;
      text.setAttribute('aria-label', '새 카드 요청 메시지'); $('actions').replaceChildren(text);
      throw new Error('이 호스트는 새 대화 요청을 지원하지 않습니다. 아래 메시지를 채팅에 붙여넣어주세요.');
    }
    if (result?.isError) throw new Error('요청 전달 결과를 확인하지 못했습니다. 같은 요청으로 다시 전달해주세요.');
    status('요청을 전달했어요. 대화에 표시되는 새 결과 카드를 확인해주세요.');
  } finally { sending = false; recovery(); }
}
async function executeIntent(intent) {
  if (isCartWrite(intent.tool)) {
    status('장바구니 이용 권한을 확인하고 있어요. 동의 창이 열리면 완료해주세요.');
    await cartPreparation.ensure();
  }
  await deliver(intent);
}
async function next(tool, args = {}) { await executeIntent(session.prepare(tool, args)); }
function recovery() {
  if (!session.pending || $('same-request')) return;
  const retry = button('같은 요청 다시 전달', () => executeIntent(session.retry()), true); retry.id = 'same-request'; $('actions').append(retry);
}
function heading(title, subtitle, eyebrow) { $('title').textContent = title; $('subtitle').textContent = subtitle; $('eyebrow').textContent = eyebrow; }
function productRail(label) {
  const section = el('section'), controls = el('div', null, 'rail-controls'), grid = el('div', null, 'search-grid');
  grid.setAttribute('role', 'region'); grid.setAttribute('aria-label', label); grid.tabIndex = 0;
  for (const [text, direction, title] of [['‹', -1, '이전 상품'], ['›', 1, '다음 상품']]) {
    const control = button(text, () => grid.scrollBy({ left: direction * 232, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }), true);
    control.setAttribute('aria-label', title); controls.append(control);
  }
  const update = () => {
    controls.hidden = grid.scrollWidth <= grid.clientWidth + 1;
    controls.children[0].disabled = grid.scrollLeft <= 1;
    controls.children[1].disabled = grid.scrollLeft + grid.clientWidth >= grid.scrollWidth - 1;
  };
  grid.addEventListener('scroll', update); new ResizeObserver(update).observe(grid);
  requestAnimationFrame(update);
  section.append(controls, grid); return { section, grid };
}
function productCard(product, label) {
  const card = el('article', null, 'card'), body = el('div', null, 'card-body');
  const prices = product.offers.map(o => o.priceAmount).filter(Number.isSafeInteger);
  body.append(el('h2', product.name, 'name'), el('p', prices.length ? `${amount(Math.min(...prices))}${product.offers.length > 1 ? '부터' : ''}` : '가격 미확정', 'price'));
  if (label) body.append(el('span', label, 'match-label'));
  body.append(el('p', [...new Set(product.offers.map(o => o.merchantName))].join(' · '), 'price-caption'));
  for (const offer of product.offers) body.append(button(product.offers.length > 1 ? `${offer.merchantName} 옵션 선택` : '옵션 선택',
    () => next('get_product_detail', { merchantId: offer.merchantId, merchantProductId: offer.merchantProductId })));
  card.append(productImage(product.imageUrl), body); return card;
}
function renderSearch(data) {
  heading('상품 검색 결과', '', '');
  const { section, grid } = productRail('검색 상품');
  for (const p of data.products ?? []) grid.append(productCard(p, ''));
  if (data.relatedSearches?.length) {
    for (const related of data.relatedSearches) {
      for (const p of related.products ?? []) grid.append(productCard(p, `${related.query?.query ?? '유사 상품'} · 비슷한 상품`));
      if (related.unavailable || related.failedMerchants?.length) section.append(el('p', `${related.query?.query ?? '연관 검색'} 일부 결과를 불러오지 못했어요.`, 'sub'));
      if (related.nextCursor || related.unavailable) section.append(button(`${related.query?.query} 더 보기 ↗`, () => next('search_products', { ...related.query, ...(related.nextCursor ? { cursor: related.nextCursor } : {}) }), true));
    }
  }
  if (!grid.children.length) grid.append(el('p', '조건에 맞는 상품이 없어요. 채팅에서 조건을 조정해주세요.', 'empty'));
  if (data.failedMerchants?.length) section.append(el('p', '일부 판매처가 응답하지 않아 확인된 상품만 보여드려요.', 'sub search-note'));
  if (data.nextCursor) section.append(button('상품 더 보기', () => next('search_products', { ...data.query, cursor: data.nextCursor }), true));
  $('content').append(section);
}
function renderDetail(data) {
  heading('옵션 선택', '', '');
  const layout = el('div', null, 'view-detail'), panel = el('div', null, 'detail-panel');
  panel.append(el('p', data.merchantName || '선택한 판매처', 'product-meta'), el('h2', data.name));
  const price = el('p', '', 'price'), stock = el('p', '', 'sub'), select = el('select'); select.setAttribute('aria-label', '상품 옵션');
  select.add(new Option('옵션을 선택해주세요', ''));
  for (const v of data.variants) { const option = new Option(`${variantLabel(data, v)} · ${amount(v.priceAmount)}`, v.merchantVariantId); option.disabled = !v.purchasable; select.add(option); }
  if (data.variants.length === 1 && data.variants[0].purchasable) select.value = data.variants[0].merchantVariantId;
  const options = el('label', '필수 옵션', 'detail-options'); options.append(select);
  const quantity = el('input'); quantity.type = 'number'; quantity.step = '1'; quantity.value = '1';
  if (selection?.merchantId === data.merchantId && selection?.merchantProductId === data.merchantProductId) {
    if (data.variants.some(v => v.merchantVariantId === selection.merchantVariantId && v.purchasable)) select.value = selection.merchantVariantId;
    if (Number.isInteger(selection.quantity) && selection.quantity > 0) quantity.value = selection.quantity;
  }
  const remember = () => { selection = { merchantId: data.merchantId, merchantProductId: data.merchantProductId,
    merchantVariantId: select.value, quantity: Number(quantity.value) }; saveSelection(); };
  quantity.onchange = remember;
  const qty = el('label', '수량'); qty.append(quantity);
  const add = button('장바구니에 담기 ↗', async () => {
    if (session.pending) { recovery(); throw new Error('이전 요청의 새 장바구니 카드를 확인해주세요.'); }
    const v = data.variants.find(v => v.merchantVariantId === select.value), count = Number(quantity.value);
    if (!v?.purchasable || !Number.isInteger(count) || count < v.minQuantity || count > v.maxQuantity) throw new Error('구매 가능한 옵션과 수량을 확인해주세요.');
    remember(); status('장바구니 이용 권한을 확인하고 있어요. 동의 창이 열리면 완료해주세요.');
    const cart = await cartPreparation.ensure();
    await next('add_cart_item', { cartVersion: cart.version, merchantId: data.merchantId,
      merchantProductId: data.merchantProductId, merchantVariantId: v.merchantVariantId, quantity: count });
  });
  const update = () => { const v = data.variants.find(v => v.merchantVariantId === select.value);
    price.textContent = v ? amount(v.priceAmount) : '옵션 선택 후 가격 확인';
    stock.textContent = v ? `${availability(v.availability)}${v.stockQuantity == null ? '' : ` · 재고 ${v.stockQuantity}개`}` : '';
    quantity.min = v?.minQuantity ?? 1; quantity.max = v?.maxQuantity ?? 99; add.disabled = !v?.purchasable;
  }; select.onchange = () => { update(); remember(); }; update();
  const actions = el('div', null, 'cart-add-actions'); actions.append(qty, add);
  const facts = el('div', null, 'detail-facts'); facts.append(stock, el('p', `배송비 ${amount(data.shipping.feeAmount)} · 배송지에 따라 최종 확정`, 'sub'));
  panel.append(price, options, facts, actions); layout.append(productImage(data.imageUrl), panel); $('content').append(layout);
}
function renderCart(data) {
  heading(`장바구니 · ${data.items.length}개 상품`, '', '');
  const { section: list, grid } = productRail('장바구니 상품');
  if (!data.items.length) list.append(el('p', '장바구니가 비어 있어요. 채팅에서 원하는 상품을 찾아보세요.', 'empty'));
  const merchants = new Map(data.merchantSummaries.map(m => [m.merchantId, m.merchantName]));
  for (const item of data.items) if (!merchants.has(item.merchantId)) merchants.set(item.merchantId, '판매처');
  for (const [id, name] of merchants) {
    for (const item of data.items.filter(i => i.merchantId === id)) {
      const row = el('article', null, 'card'), info = el('div', null, 'card-body');
      info.append(el('h2', item.name, 'name'), el('p', amount(item.lineTotalAmount), 'price'), el('p', `${item.selectedOptions.map(o => o.value).join(' · ')} · ${item.quantity}개`, 'sub'), el('p', name, 'price-caption'));
      const actions = el('div', null, 'cart-edit'), quantity = el('input'); quantity.type = 'number'; quantity.min = '1'; quantity.max = '99'; quantity.value = item.quantity;
      const label = el('label', '수량'); label.append(quantity);
      actions.append(label, button('수량 변경 ↗', () => {
        const count = Number(quantity.value); if (!Number.isInteger(count) || count < 1 || count > 99) throw new Error('수량은 1~99 사이로 입력해주세요.');
        return next('update_cart_item', { cartVersion: data.version, cartItemId: item.cartItemId, merchantVariantId: item.merchantVariantId, quantity: count });
      }, true), button('삭제 ↗', () => next('delete_cart_item', { cartVersion: data.version, cartItemId: item.cartItemId }), true));
      info.append(actions); row.append(productImage(item.imageUrl), info); grid.append(row);
    }
  }
  const summary = el('div', null, 'summary-panel'), total = el('div', null, 'cart-total');
  total.append(el('span', '예상 결제금액'), el('strong', amount(data.summary.totalAmount)));
  summary.append(total, el('p', `할인 ${amount(data.summary.discountAmount)} · 배송비 ${amount(data.summary.shippingAmount)}`, 'sub'),
    el('p', '최종 금액과 배송지는 앱에서 확인합니다. 결제 요청만으로 결제되지는 않아요.', 'sub'));
  const purchase = button('앱으로 결제 요청 ↗', () => next('prepare_purchase_handoff', { cartId: data.cartId, cartVersion: data.version })); purchase.disabled = !data.items.length;
  summary.append(purchase); $('content').append(list, summary); $('actions').append(button('최신 장바구니 새로 보기 ↗', () => next('get_cart'), true));
}
function renderPurchase(data, kind) {
  heading('결제 승인은 앱에서', '휴대폰 알림을 열고 주문 내용과 최종 금액을 확인해주세요.', '04 / APPROVAL');
  const panel = el('div', null, 'purchase-panel');
  const progress = kind === 'purchase.status' ? purchaseProgress(data) : { text: '구매 요청이 접수됐어요. 현재 진행 상태를 확인해주세요.' };
  panel.append(el('div', '↗', 'purchase-symbol'), el('h2', kind === 'purchase.handoff' ? '구매 요청을 전달했어요' : '구매 진행 상태'), el('p', progress.text));
  const steps = el('div', null, 'purchase-steps'); for (const label of ['주문 확인', '앱 알림', '사용자 승인']) steps.append(el('span', label)); panel.append(steps);
  panel.append(el('p', `요청 ${data.purchaseRequestId}`, 'receipt-id'));
  if (data.expiresAt) panel.append(el('p', `요청 유효 시각 ${new Date(data.expiresAt).toLocaleString('ko-KR')}`, 'sub'));
  $('content').append(panel);
  $('actions').append(button('현재 결제 상태 보기 ↗', () => next('get_purchase_status', { purchaseRequestId: data.purchaseRequestId })));
  if (!progress.terminal) $('actions').append(button('알림 다시 요청 ↗', () => next('resend_purchase_notification', { purchaseRequestId: data.purchaseRequestId }), true));
  if (data.status === 'EXPIRED') {
    panel.append(el('p', '요청이 만료됐어요. 최신 장바구니와 구매 상태를 확인해주세요.', 'sub'));
    $('actions').append(button('최신 장바구니 보기 ↗', () => next('get_cart'), true));
  }
}
function accept(result) {
  if (internalReads) return;
  const value = result?.structuredContent ?? result;
  if (result?.isError || value?.error) {
    heading('요청 결과를 확인해주세요', '오류가 발생해도 이전 대화의 상품과 장바구니 카드는 남아 있어요.', 'COPENHAGEN');
    $('content').replaceChildren(el('p', value?.error?.message ?? '요청을 완료하지 못했어요. 채팅에서 같은 요청의 결과 확인을 요청해주세요.', 'empty'));
    status(''); recovery(); return;
  }
  if (!viewAccepts(view, value?.kind) || !value.data) return;
  latest = value; $('content').replaceChildren(); $('actions').replaceChildren(); status('');
  try {
    if (view === 'search') renderSearch({ ...value.data, query: value.data.query ?? latestInput });
    else if (view === 'detail') renderDetail(value.data);
    else if (view === 'cart') {
      const cart = value.kind === 'cart.mutation' ? value.data.cart : value.data;
      if (cart) renderCart(cart);
      else { heading(value.data.outcome === 'APPLIED' ? '장바구니 변경을 처리했어요' : '장바구니 상태를 확인해주세요', '최신 장바구니를 새 카드에서 확인해주세요.', '03 / YOUR BAG'); $('content').append(button('장바구니 새로 보기 ↗', () => next('get_cart'))); }
    } else renderPurchase(value.data, value.kind);
    $('checked-at').textContent = value.data.checkedAt ? new Date(value.data.checkedAt).toLocaleTimeString('ko-KR') : '';
    recovery(); sizing.notify();
  } catch { status('표시할 정보를 확인하지 못했어요. 채팅에서 최신 결과를 다시 요청해주세요.'); }
}
app = new App({ name: 'Copenhagen Cards', version: '1.0.0' }, { availableDisplayModes: ['inline'] }, { autoResize: false });
app.ontoolresult = accept; app.ontoolinput = input => { latestInput = input.arguments ?? {}; };
app.connect().then(() => { ready = true; sizing.notify(); }).catch(() => { if (!latest) status('ChatGPT 연결을 기다리고 있어요.'); });
if (window.openai?.toolOutput) accept(window.openai.toolOutput);
window.addEventListener('openai:set_globals', event => { if (event.detail?.globals?.toolOutput) accept(event.detail.globals.toolOutput); });
