import test from 'node:test';
import assert from 'node:assert/strict';
import { sendAction } from './commerce-actions.js';
const reference = '00000000-0000-4000-8000-000000000001';
test('selection arguments travel privately and only the reference reaches chat', async () => {
  const intent = { tool: 'get_product_detail', args: { merchantId: 'merchant-c', merchantProductId: 'onion-1' } };
  let stored;
  const host = { callServerTool: async request => {
    assert.equal(request.name, 'prepare_widget_action'); stored = structuredClone(request.arguments);
    return { structuredContent: { kind: 'action.reference', data: { reference } } };
  }, sendMessage: async message => {
    const text = message.content[0].text;
    assert.match(text, /선택한 상품의 옵션/); assert.ok(text.includes(reference));
    for (const hidden of ['merchantId', 'onion-1', 'get_product_detail', 'idempotencyKey', '{']) assert.ok(!text.includes(hidden));
  } };
  await sendAction(host, intent, fn => fn());
  assert.deepEqual(stored, { tool: intent.tool, arguments: intent.args });
});
test('failed or malformed preparation sends no ambiguous chat message', async () => {
  for (const response of [{ isError: true }, { structuredContent: { kind: 'action.reference', data: { reference: 'invalid' } } }]) {
    await assert.rejects(sendAction({ callServerTool: async () => response,
      sendMessage: () => assert.fail('must not send') }, { tool: 'get_cart', args: {} }, fn => fn()));
  }
});
test('uncertain send never automatically retries or changes mutation key', async () => {
  let calls = 0;
  const intent = { tool: 'add_cart_item', args: { idempotencyKey: reference, quantity: 2 } };
  await assert.rejects(sendAction({ callServerTool: async request => {
    assert.deepEqual(request.arguments.arguments, intent.args);
    return { structuredContent: { kind: 'action.reference', data: { reference } } };
  }, sendMessage: async () => { calls++; throw Error('timeout'); } }, intent, fn => fn()), /timeout/);
  assert.equal(calls, 1); assert.equal(intent.args.idempotencyKey, reference);
});
