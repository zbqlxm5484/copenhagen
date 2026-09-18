import { App } from '@modelcontextprotocol/ext-apps';
import { createHostSizing } from './host-sizing.js';
import { purchaseProgress } from './purchase-progress.js';
import { safeImage, availability, timedCall } from './catalog.js';
import { amount, payload, searchArguments, variantLabel, pendingRequest, restorablePending } from './live-state.js';

const $ = id => document.getElementById(id);
const el = (tag, text, className) => { const node = document.createElement(tag); node.textContent = text ?? ''; if (className) node.className = className; return node; };
const button = (text, action, secondary = false) => { const node = el('button', text, secondary ? 'secondary' : ''); node.onclick = () => void action(); return node; };
const note = text => { $('status').textContent = text; $('cart-message').textContent = text; const detailNote = $('live-detail-note'); if (detailNote) detailNote.textContent = text; };
let app, ready = false, currentSearch, currentCart, query = {}, cursors = [null], page = 0, busy = false;
let pending = restorablePending(window.openai?.widgetState?.livePending), receipt = window.openai?.widgetState?.liveReceipt;
let progressTimer, progressBusy = false, resendKey;
let generation = 0, detailGeneration = 0, cartGeneration = 0, observedMode;
const sizing = createHostSizing({ getApp: () => ready ? app : null,
  getDisplayState: () => ({ mode: observedMode, confirmed: observedMode !== undefined }),
  measure: () => ({ width: window.innerWidth, height: document.querySelector('main').getBoundingClientRect().height }) });
new ResizeObserver(() => sizing.notify()).observe(document.querySelector('main'));
function observeHostMode(context) {
  if (['inline', 'fullscreen', 'pip'].includes(context?.displayMode)) observedMode = context.displayMode;
  sizing.notify();
}
observeHostMode(window.openai);
window.addEventListener('resize', () => sizing.notify(), { passive: true });
function save() { window.openai?.setWidgetState?.({ version: 4, livePending: pending, liveQuery: query, liveReceipt: receipt }); }
function controls() {
  for (const id of ['cart-retry', 'cart-dialog-retry', 'handoff-retry']) { $(id).hidden = !pending; $(id).disabled = busy; }
  $('previous').disabled = busy || page === 0;
  $('next').disabled = busy || !currentSearch?.nextCursor;
  $('cart-continue').disabled = busy || !!pending || !currentCart?.items?.length;
}
async function raw(name, args) {
  return timedCall(() => ready ? app.callServerTool({ name, arguments: args })
    : window.openai?.callTool ? window.openai.callTool(name, args) : Promise.reject(new Error('연결을 기다려주세요.')));
}
async function call(name, args, kind) { return payload(await raw(name, args), kind); }
function show(id) { for (const dialog of document.querySelectorAll('dialog[open]')) dialog.close(); if (!$(id).open) $(id).showModal(); }
function picture(url) {
  const holder = el('div', '', 'picture'); const safe = safeImage(url);
  if (safe) { const image = document.createElement('img'); image.src = safe; image.alt = ''; image.loading = 'lazy'; holder.append(image); }
  else holder.textContent = '상품 이미지'; return holder;
}
function selectOptions(id, values, label, selected = '') {
  const node = $(id); node.replaceChildren(new Option(label, ''));
  for (const value of values) node.add(new Option(value.name, value.id)); node.value = selected;
}
function renderSearch(data) {
  currentSearch = data; $('cards').replaceChildren(); $('cards').dataset.count = data.products.length;
  for (const product of data.products) {
    const card = el('article', '', 'card'), body = el('div', '', 'card-body');
    const prices = product.offers.map(o => o.priceAmount).filter(Number.isSafeInteger);
    body.append(el('h2', product.name, 'name'), el('p', prices.length ? `${amount(Math.min(...prices))}부터` : '가격 미확정', 'price'),
      button(`판매처 ${product.offers.length}곳 비교 →`, () => compare(product)));
    card.append(picture(product.imageUrl), body); $('cards').append(card);
  }
  if (!data.products.length) $('cards').append(el('p', data.failedMerchants.length ? '판매처 조회에 실패했습니다.' : '검색 결과가 없습니다.', 'empty'));
  note(data.failedMerchants.length ? `일부 판매처 조회 실패: ${data.failedMerchants.join(', ')}. 다시 검색할 수 있어요.` : '상품을 선택해 판매처별 가격을 확인하세요.');
  selectOptions('category', data.filters.categories, '전체 상품군', query.categoryId); selectOptions('merchantId', data.filters.merchants, '전체 판매처', query.merchantId);
  $('page').textContent = `${page + 1} 페이지`; $('checked-at').textContent = new Date(data.checkedAt).toLocaleTimeString('ko-KR');
  $('notice').textContent = '가격·재고는 조회 시점 기준'; $('carousel-controls').hidden = data.products.length < 2;
  $('cards').scrollLeft = 0; controls(); sizing.notify();
}
async function search(nextQuery, nextPage = 0) {
  const id = ++generation; note('상품을 조회하고 있습니다.');
  try {
    const args = { ...nextQuery }; if (cursors[nextPage]) args.cursor = cursors[nextPage];
    const result = await call('search_products', args, 'catalog.search');
    if (id !== generation) return;
    query = nextQuery; page = nextPage; cursors[page + 1] = result.nextCursor; renderSearch(result); save();
  } catch (error) { if (id === generation) note(error.message); }
}
function compare(product) {
  detailGeneration++;
  $('comparison-title').textContent = '판매처 비교'; $('comparison-body').replaceChildren(el('h3', product.name, 'dialog-product'));
  $('comparison-actions').replaceChildren();
  for (const offer of product.offers) {
    const row = el('div', '', 'offer'), info = el('div');
    info.append(el('p', offer.merchantName), el('p', amount(offer.priceAmount), 'price'), el('p', availability(offer.availability), 'sub'));
    row.append(info, button('옵션 · 재고 확인', () => detail(offer, product))); $('comparison-body').append(row);
  }
  show('comparison');
}
async function detail(offer, product, item = null) {
  const request = ++detailGeneration;
  try {
    const data = await call('get_product_detail', { merchantId: offer.merchantId, merchantProductId: offer.merchantProductId }, 'catalog.detail');
    if (request !== detailGeneration) return;
    $('comparison-title').textContent = item ? '옵션 · 수량 변경' : '옵션 · 재고 확인';
    const body = $('comparison-body'); body.replaceChildren(el('h3', data.name, 'dialog-product'), el('p', offer.merchantName ?? '', 'sub'));
    const select = document.createElement('select'); select.setAttribute('aria-label', '색상·사이즈 옵션');
    select.add(new Option('옵션을 선택해주세요', ''));
    for (const variant of data.variants) {
      const option = new Option(`${variantLabel(data, variant)} · ${amount(variant.priceAmount)} · ${availability(variant.availability)}`, variant.merchantVariantId);
      option.disabled = !variant.purchasable; select.add(option);
    }
    if (item) select.value = item.merchantVariantId;
    else if (data.variants.length === 1 && data.variants[0].purchasable) select.value = data.variants[0].merchantVariantId;
    const price = el('p', '', 'price'), stock = el('p', '', 'sub'), label = el('label', '필수 옵션', 'detail-options'); label.append(select);
    body.append(price, label, stock, el('p', `배송비 ${amount(data.shipping.feeAmount)} · 배송지 선택 후 확정`, 'sub'));
    const quantity = document.createElement('input'); quantity.type = 'number'; quantity.step = '1'; quantity.value = item?.quantity ?? 1;
    const update = () => {
      const variant = data.variants.find(v => v.merchantVariantId === select.value);
      price.textContent = variant ? amount(variant.priceAmount) : '옵션을 선택해주세요.';
      stock.textContent = variant ? `${availability(variant.availability)}${variant.stockQuantity == null ? '' : ` · ${variant.stockQuantity}개`}` : '';
      quantity.min = variant?.minQuantity ?? 1; quantity.max = variant?.maxQuantity ?? 99;
    }; select.onchange = update; update();
    const actions = el('div', '', 'cart-add-actions'), quantityLabel = el('label', '수량'); quantityLabel.append(quantity);
    actions.append(quantityLabel, button(item ? '변경 적용' : '장바구니 담기', async () => {
      if (busy || pending) { note('이전 변경 결과를 먼저 확인해주세요.'); return; }
      const variant = data.variants.find(v => v.merchantVariantId === select.value), count = Number(quantity.value);
      if (!variant?.purchasable || !Number.isInteger(count) || count < variant.minQuantity || count > variant.maxQuantity) { stock.textContent = '구매 가능한 옵션과 수량을 선택해주세요.'; return; }
      if (!currentCart) await refreshCart(false);
      if (!currentCart) return;
      await mutate(item ? 'update_cart_item' : 'add_cart_item', { cartVersion: currentCart.version,
        ...(item ? { cartItemId: item.cartItemId } : { merchantId: offer.merchantId, merchantProductId: offer.merchantProductId }),
        merchantVariantId: variant.merchantVariantId, quantity: count });
    }));
    const detailNote = el('p', '', 'sub'); detailNote.id = 'live-detail-note'; detailNote.setAttribute('role', 'status');
    $('comparison-actions').replaceChildren(actions, detailNote); show('comparison');
  } catch (error) { note(error.message); }
}
function renderCart() {
  const body = $('cart-body'); body.replaceChildren();
  const merchants = new Map(currentCart.merchantSummaries.map(m => [m.merchantId, m]));
  for (const item of currentCart.items) if (!merchants.has(item.merchantId)) merchants.set(item.merchantId, { merchantId: item.merchantId, merchantName: item.merchantId });
  for (const merchant of merchants.values()) {
    body.append(el('h3', merchant.merchantName, 'cart-group-heading'));
    for (const item of currentCart.items.filter(i => i.merchantId === merchant.merchantId)) {
      const row = el('div', '', 'cart-line'), content = el('div');
      content.append(el('p', item.name, 'cart-name'), el('p', `${item.selectedOptions.map(o => o.value).join(' · ')} · ${item.quantity}개`, 'cart-option sub'));
      const actions = el('div', '', 'line-actions'); actions.append(el('span', amount(item.lineTotalAmount), 'price'),
        button('변경', () => detail({ ...item, merchantName: merchant.merchantName }, item, item), true),
        button('삭제', () => mutate('delete_cart_item', { cartVersion: currentCart.version, cartItemId: item.cartItemId }), true));
      content.append(actions); row.append(content); body.append(row);
    }
  }
  if (!currentCart.items.length) body.append(el('p', '장바구니가 비어 있어요.', 'empty'));
  const total = currentCart.summary;
  $('cart-summary').replaceChildren(el('div', `예상 결제금액 ${amount(total.totalAmount)}`, 'cart-total'),
    el('p', `할인 ${amount(total.discountAmount)} · 배송비 ${amount(total.shippingAmount)}`),
    el('p', '기본 배송지로 주문을 확인한 뒤 앱에 승인 알림을 보냅니다. 최종 금액은 앱에서 확인해주세요.'));
  $('cart-open').textContent = `장바구니 (${currentCart.items.reduce((n, i) => n + i.quantity, 0)})`; controls();
}
async function refreshCart(open = true) {
  const request = ++cartGeneration;
  try { const data = await call('get_cart', {}, 'cart.view'); if (request !== cartGeneration) return;
    acceptCart(data); if (open) show('cart-dialog'); }
  catch (error) { note(error.message); throw error; }
}
function acceptCart(data) {
  if (currentCart?.cartId === data.cartId && data.version < currentCart.version) return;
  currentCart = data; renderCart();
}
async function mutate(tool, args) {
  if (busy || pending) { note('이전 요청 결과를 먼저 확인해주세요.'); return; }
  pending = pendingRequest(tool, args); save(); controls(); await retryPending();
}
async function retryPending() {
  if (!pending || busy) return;
  if (Date.now() - pending.createdAt >= 86400000) { note('요청 확인 기한이 지났습니다. 중복 처리를 막기 위해 Core 담당자에게 결과 확인을 요청해주세요.'); return; }
  busy = true; controls();
  const request = pending;
  try {
    const purchase = request.tool === 'prepare_purchase_handoff';
    const data = await call(request.tool, request.args, purchase ? 'purchase.handoff' : 'cart.mutation');
    if (!purchase && data.outcome !== 'APPLIED') throw new Error('변경 결과를 확인하지 못했습니다.');
    pending = null; save();
    if (purchase) { receipt = data; save(); renderHandoff(); }
    else {
      note('장바구니에 반영했습니다.');
      if (data.cart) { cartGeneration++; acceptCart(data.cart); show('cart-dialog'); }
      else { try { await refreshCart(); } catch { note('변경은 완료됐습니다. 장바구니 조회만 다시 시도해주세요.'); } }
    }
  } catch (error) {
    if (error.outcome === 'NOT_APPLIED') { pending = null; save(); }
    note(error.message); $('handoff-status').textContent = error.message;
    if (error.code === 'CART_VERSION_CONFLICT') { try { await refreshCart(); } catch { /* 조회 버튼 유지 */ } }
  } finally { busy = false; controls(); }
}
function renderHandoff() {
  $('handoff-title').textContent = '앱에서 결제 승인';
  $('handoff-status').textContent = '구매 요청이 준비됐습니다. 기본 배송지로 검증한 뒤 앱에 결제 승인 알림을 보냅니다.';
  $('handoff-expiry').textContent = `${new Date(receipt.expiresAt).toLocaleTimeString('ko-KR')}까지 유효`;
  show('handoff-dialog');
  if (!$('purchase-refresh')) {
    const refresh = button('결제 상태 확인', () => refreshPurchase()); refresh.id = 'purchase-refresh';
    const resend = button('알림 다시 보내기', () => refreshPurchase(true), true); resend.id = 'purchase-resend';
    $('handoff-status').after(refresh, resend);
  }
  void refreshPurchase();
}

async function refreshPurchase(resend = false) {
  if (progressBusy || !receipt?.purchaseRequestId) return;
  progressBusy = true; clearTimeout(progressTimer);
  try {
    const args = { purchaseRequestId: receipt.purchaseRequestId };
    if (resend) args.idempotencyKey = resendKey ??= crypto.randomUUID();
    const data = await call(resend ? 'resend_purchase_notification' : 'get_purchase_status', args, 'purchase.status');
    if (resend) resendKey = undefined;
    const progress = purchaseProgress(data);
    $('handoff-status').textContent = progress.text;
    $('purchase-resend').hidden = progress.terminal;
    if (!progress.terminal && $('handoff-dialog').open && !document.hidden) progressTimer = setTimeout(() => void refreshPurchase(), 5000);
  } catch (error) { $('handoff-status').textContent = '현재 상태를 확인하지 못했습니다. 결제 상태 확인을 다시 눌러주세요.'; }
  finally { progressBusy = false; }
}
$('search-form').onsubmit = event => {
  event.preventDefault(); try { const fields = Object.fromEntries(['query', 'merchantId', 'brand', 'sort', 'minPrice', 'maxPrice'].map(id => [id, $(id).value]));
    fields.categoryId = $('category').value; cursors = [null]; void search(searchArguments(fields)); } catch (error) { note(error.message); }
};
$('reset').onclick = () => { $('search-form').reset(); cursors = [null]; void search({ pageSize: 4 }); };
$('previous').onclick = () => void search(query, page - 1); $('next').onclick = () => void search(query, page + 1);
for (const [buttonId, dialogId] of [['close-dialog', 'comparison'], ['cart-close', 'cart-dialog'], ['handoff-close', 'handoff-dialog']]) $(buttonId).onclick = () => { detailGeneration++; $(dialogId).close(); };
$('cart-open').hidden = false; $('cart-bar').hidden = false; $('cart-open').onclick = () => void refreshCart().catch(() => {});
$('cart-confirm').textContent = '새로고침'; $('cart-confirm').onclick = () => void refreshCart().catch(() => {});
$('cart-continue').textContent = '구매 진행'; $('cart-continue').onclick = () => {
  if (!currentCart) return;
  if (receipt?.cartId === currentCart.cartId && receipt?.cartVersion === currentCart.version) { renderHandoff(); return; }
  void mutate('prepare_purchase_handoff', { cartId: currentCart.cartId, cartVersion: currentCart.version });
};
for (const id of ['cart-retry', 'cart-dialog-retry', 'handoff-retry']) $(id).onclick = () => void retryPending();
$('handoff-back').onclick = () => void refreshCart().catch(() => {});
for (const [id, direction] of [['slide-previous', -1], ['slide-next', 1]]) $(id).onclick = () => $('cards').scrollBy({ left: direction * $('cards').clientWidth * .84, behavior: 'smooth' });
$('cards').onscroll = () => { $('slide-position').textContent = `${Math.round($('cards').scrollLeft / Math.max(1, $('cards').firstElementChild?.getBoundingClientRect().width ?? 1)) + 1} / ${currentSearch?.products.length ?? 0}`; };
function hostQuery(input) {
  query = { ...input }; page = 0; cursors = [input.cursor ?? null];
  for (const id of ['query', 'merchantId', 'brand', 'sort', 'minPrice', 'maxPrice']) $(id).value = input[id] ?? '';
}
const accept = result => { try { const data = payload(result, 'catalog.search'); cursors[page + 1] = data.nextCursor; renderSearch(data); } catch { /* 다른 도구 결과는 무시 */ } };
app = new App({ name: 'Copenhagen', version: '1.0.0' }); app.ontoolresult = accept;
app.onhostcontextchanged = observeHostMode;
app.ontoolinput = input => hostQuery(input.arguments ?? {});
app.connect().then(() => { ready = true; controls(); }).catch(() => note('연결을 확인해주세요.'));
if (window.openai?.toolInput) hostQuery(window.openai.toolInput);
if (window.openai?.toolOutput) accept(window.openai.toolOutput);
window.addEventListener('openai:set_globals', event => { observeHostMode(event.detail?.globals); if (event.detail?.globals?.toolOutput) accept(event.detail.globals.toolOutput); });
controls();
