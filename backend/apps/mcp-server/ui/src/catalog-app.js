import { App } from '@modelcontextprotocol/ext-apps';
import { createCartUi } from './cart-app.js';
import { acceptHostSearch, availability, detailData, detailPrompt, filters, money, safeImage, searchData, timedCall } from './catalog.js';

const $ = id => document.getElementById(id);
const formFields = ['query', 'category', 'merchantId', 'minPrice', 'maxPrice', 'brand', 'sort'];
let app, sdkReady = false, connecting = false, busy = false, generation = 0, current, lastQuery = {};
let detailGeneration = 0;
const element = (tag, text, className) => {
  const item = document.createElement(tag); if (text !== undefined) item.textContent = text;
  if (className) item.className = className; return item;
};
const cart = createCartUi(callTool, element, saveWidgetState);
function saveWidgetState() {
  window.openai?.setWidgetState?.({ version: 2, query: lastQuery, cartPending: cart.pending() });
}
function status(message, error = false) { $('status').textContent = message; $('status').dataset.error = String(error); }
function controls() {
  for (const control of document.querySelectorAll('form input,form select,form button,#retry')) control.disabled = busy;
  $('previous').disabled = busy || !current || current.query.page <= 1;
  $('next').disabled = busy || !current?.hasNext;
}
async function callTool(name, args) {
  try {
    return await timedCall(() => {
      if (sdkReady) return app.callServerTool({ name, arguments: args });
      if (typeof window.openai?.callTool === 'function') return window.openai.callTool(name, args);
      throw new Error('Host unavailable');
    });
  } catch { throw new Error('상품 서버에 연결할 수 없습니다. 다시 시도해주세요.'); }
}
function options(id, values, placeholder, selected) {
  const select = $(id); select.replaceChildren(new Option(placeholder, ''));
  for (const [value, label] of values) select.add(new Option(label, value));
  if (selected && !values.some(([value]) => value === selected)) select.add(new Option(selected, selected));
  select.value = selected ?? '';
}
function compare(product) {
  ++detailGeneration;
  $('comparison-title').textContent = '판매처 비교';
  const body = $('comparison-body'); body.replaceChildren(element('p', product.name));
  if (current.mode === 'QA') body.append(element('p', '로컬 QA 가상 가격 · 실제 구매 가격이 아닙니다.', 'muted'));
  for (const offer of product.offers) {
    const row = element('section', undefined, 'offer');
    row.append(element('div', offer.merchantName), element('div', money(offer.price), 'price'),
      element('div', availability(offer.availability), 'sub'));
    const detail = element('button', '옵션 · 재고 확인');
    detail.addEventListener('click', () => void loadDetail({ productId: product.productId, merchantId: offer.merchantId }));
    row.append(detail);
    body.append(row);
  }
  $('comparison').showModal();
}
async function loadDetail(request) {
  const id = ++detailGeneration; const body = $('comparison-body');
  $('comparison-title').textContent = '옵션 · 재고 확인';
  body.replaceChildren(element('p', '상품 상세 정보를 조회하고 있습니다.'));
  try {
    const data = detailData(await callTool('get_product_detail', request), request);
    if (id !== detailGeneration || !$('comparison').open) return;
    if (data.status === 'NOT_FOUND') { body.replaceChildren(element('p', '해당 판매처에서 상품을 찾을 수 없습니다.')); return; }
    const p = data.product;
    body.replaceChildren(element('p', p.name), element('p', p.offer.merchantName, 'sub'));
    if (data.mode === 'QA') body.append(element('p', '가상 가격 · 수집 시점의 옵션과 판매 상태', 'muted'));
    body.append(element('p', p.selectionStatus === 'REQUIRED' ? '옵션 선택 후 가격을 확인할 수 있어요.'
      : p.selectionStatus === 'INVALID' ? '유효하지 않은 옵션입니다. 다시 선택해주세요.' : money(p.offer.price), 'price'));
    if (p.variants.length) {
      const label = element('label', p.optionsRequired ? '필수 옵션' : '확인된 옵션');
      const select = element('select'); select.add(new Option('옵션을 선택해주세요', ''));
      for (const option of p.variants) select.add(new Option(`${option.label} · ${money(option.price)} · ${availability(option.availability)}`, option.optionId));
      select.value = p.selectedOptionId ?? '';
      select.addEventListener('change', () => {
        const args = { productId: request.productId, merchantId: request.merchantId };
        if (select.value) args.optionId = select.value;
        void loadDetail(args);
      });
      label.append(select); body.append(label);
    } else body.append(element('p', '확인된 옵션 정보가 없습니다.', 'sub'));
    if (!p.optionsComplete) body.append(element('p', '일부 옵션만 표시됩니다. 전체 옵션은 아직 확인되지 않았습니다.', 'sub'));
    body.append(element('p', `${availability(p.offer.availability)} · 수량 ${p.stockQuantity === null ? '미확인' : p.stockQuantity}`, 'sub'));
    const selected = p.variants.find(v => v.optionId === p.selectedOptionId);
    if (selected?.purchaseAllowed === false) body.append(element('p', '수집 시점에 구매 불가로 표시된 옵션입니다.', 'sub'));
    body.append(element('p', p.shipping.status === 'UNKNOWN' ? '배송 조건 · 배송비 미확인'
      : `${p.shipping.description} · 배송비 ${money(p.shipping.fee)}`, 'sub'));
    const explain = element('button', '채팅으로 자세히 설명');
    cart.appendAdd(body, p, request);
    explain.addEventListener('click', async () => {
      explain.disabled = true;
      try {
        const prompt = detailPrompt(request);
        if (sdkReady) {
          const result = await timedCall(() => app.sendMessage({ role: 'user', content: [{ type: 'text', text: prompt }] }));
          if (result?.isError) throw new Error('채팅 요청이 거절되었습니다. 다시 시도해주세요.');
        } else if (typeof window.openai?.sendFollowUpMessage === 'function') {
          await timedCall(() => window.openai.sendFollowUpMessage({ prompt }));
        } else {
          const note = element('p', '로컬 미리보기에는 ChatGPT 채팅이 연결되지 않았습니다. 아래 요청을 ChatGPT에서 사용하세요.', 'sub');
          const text = element('textarea'); text.readOnly = true; text.value = prompt; text.rows = 5;
          text.style.width = '100%'; text.setAttribute('aria-label', 'ChatGPT 상세 요청');
          body.append(note, text); return;
        }
        explain.textContent = '채팅에 요청했어요';
      } catch (error) { body.append(element('p', error.message, 'sub')); explain.disabled = false; }
    });
    body.append(explain, element('p', `확인 ${new Date(data.checkedAt).toLocaleTimeString('ko-KR', { hour12: false })}`, 'muted'));
  } catch (error) {
    if (id !== detailGeneration || !$('comparison').open) return;
    const retry = element('button', '상세 다시 시도'); retry.addEventListener('click', () => void loadDetail(request));
    body.replaceChildren(element('p', error.message), retry);
  }
}
function render(data) {
  current = data; lastQuery = filters(data.query);
  $('mode').textContent = data.mode === 'QA' ? (cart.enabled ? 'LOCAL QA3' : 'LOCAL QA2') : '상품 탐색';
  $('notice').textContent = data.mode === 'QA' ? '가상몰 3곳 · 노이즈를 적용한 QA 가격 · 재고는 수집 시점 기준' : '가격과 재고는 조회 시점 기준입니다.';
  $('checked-at').textContent = `확인 ${new Date(data.checkedAt).toLocaleTimeString('ko-KR', { hour12: false })}`;
  options('category', data.categories.map(x => [x, x]), '상품군 선택', data.query.category);
  options('merchantId', data.merchants.map(x => [x.merchantId, x.name]), '전체 판매처', data.query.merchantId);
  for (const key of formFields.filter(x => !['category', 'merchantId'].includes(x))) $(key).value = data.query[key] ?? '';
  $('sort').value = data.query.sort ?? 'RELEVANCE';
  $('search-filters').open = data.status === 'CLARIFY';
  const filterCount = ['category', 'merchantId', 'minPrice', 'maxPrice', 'brand'].filter(k => lastQuery[k] !== undefined).length
    + (data.query.sort && data.query.sort !== 'RELEVANCE' ? 1 : 0);
  $('filter-label').textContent = filterCount ? `필터 ${filterCount}개 적용` : '필터 · 상품군 · 판매처';
  $('page').textContent = `${data.query.page ?? 1} 페이지 · ${data.total.toLocaleString('ko-KR')}개`;
  const cards = $('cards'); cards.replaceChildren();
  for (const product of data.products) {
    const card = element('article', undefined, 'card');
    const picture = element('div', '이미지 없음', 'picture'); const image = safeImage(product.imageUrl);
    if (image) {
      const img = element('img'); img.src = image; img.alt = ''; img.loading = 'lazy'; img.referrerPolicy = 'no-referrer';
      img.addEventListener('error', () => picture.replaceChildren(element('span', '이미지 없음')), { once: true });
      picture.replaceChildren(img);
    }
    const body = element('div', undefined, 'card-body'); const name = element('h2', product.name, 'name'); name.title = product.name;
    const prices = product.offers.map(x => x.price).filter(x => x !== null);
    const compareButton = element('button', `판매처 ${product.offers.length}곳 비교 →`);
    compareButton.addEventListener('click', () => compare(product));
    body.append(element('div', product.brand || product.category, 'muted'), name,
      element('div', prices.length ? `${money(Math.min(...prices))}부터` : '가격 미확인', 'price'), compareButton);
    card.append(picture, body); cards.append(card);
  }
  if (!data.products.length) cards.append(element('p', data.status === 'CLARIFY' ? '위에서 원하는 상품군을 선택해주세요.'
    : data.status === 'PARTIAL' ? '일부 판매처 응답이 없습니다. 다시 시도해주세요.' : '검색 결과가 없습니다. 조건을 바꿔보세요.', 'empty'));
  status(data.status === 'CLARIFY' ? '여러 상품군이 검색됐어요. 상품군을 먼저 골라주세요.'
    : data.status === 'PARTIAL' ? `일부 판매처 조회 실패 (${data.failedMerchants.length}곳) · 확인된 결과만 표시합니다.`
    : data.products.length ? '상품을 선택해 판매처별 가격을 확인하세요.' : '검색 결과 없음');
  $('retry').hidden = data.status !== 'PARTIAL';
  saveWidgetState();
  controls();
}
async function search(input) {
  if (busy) return;
  let query; try { query = filters(input); } catch (error) { status(error.message, true); return; }
  lastQuery = query; busy = true; const request = ++generation; controls(); status('상품을 조회하고 있습니다.'); $('retry').hidden = true;
  try {
    const result = searchData(await callTool('search_products', query));
    if (request === generation) render(result);
  } catch (error) {
    if (request === generation) { status(error.message, true); $('retry').hidden = false; }
  } finally { if (request === generation) { busy = false; controls(); } }
}
function formQuery() { return Object.fromEntries(formFields.map(key => [key, $(key).value])); }
$('search-form').addEventListener('submit', event => { event.preventDefault(); void search(formQuery()); });
for (const id of ['category', 'merchantId', 'sort']) $(id).addEventListener('change', () => void search(formQuery()));
$('reset').addEventListener('click', () => void search({}));
$('retry').addEventListener('click', async () => { if (!sdkReady && !window.openai?.callTool) await connect(); await search(lastQuery); });
$('previous').addEventListener('click', () => void search({ ...lastQuery, page: lastQuery.page - 1 }));
$('next').addEventListener('click', () => void search({ ...lastQuery, page: lastQuery.page + 1 }));
$('close-dialog').addEventListener('click', () => $('comparison').close());
$('comparison').addEventListener('close', () => { ++detailGeneration; });
function hostResult(result) {
  try {
    const data = searchData(result);
    if (!acceptHostSearch(data, current, busy)) return;
    ++generation; busy = false; render(data);
  } catch { /* Ignore unrelated host tool results. */ }
}
function globals(event) {
  const output = event?.detail?.globals?.toolOutput;
  if (output) hostResult(output);
}
window.addEventListener('openai:set_globals', globals);
async function connect() {
  if (connecting || sdkReady) return;
  connecting = true;
  try {
    if (app) await app.close().catch(() => {});
    app = new App({ name: 'Copenhagen Catalog', version: '2.0.0' }, { availableDisplayModes: ['inline'] }, { autoResize: true });
    app.ontoolresult = hostResult;
    await app.connect(undefined, { timeout: 5000 }); sdkReady = true;
  } catch { /* Feature-detected native bridge supports existing ChatGPT mobile hosts. */ }
  finally { connecting = false; }
}
async function start() {
  if (window.openai?.toolOutput) hostResult(window.openai.toolOutput);
  if (typeof window.openai?.callTool !== 'function') await connect();
  else void connect();
  if (!current) {
    let restored = {}; try { if ([1, 2].includes(window.openai?.widgetState?.version)) restored = filters(window.openai.widgetState.query); } catch { }
    await search(restored);
  }
  void cart.refresh().catch(() => {});
}
window.addEventListener('pageshow', () => { controls(); if (!sdkReady && !window.openai?.callTool) void connect(); });
void start();
