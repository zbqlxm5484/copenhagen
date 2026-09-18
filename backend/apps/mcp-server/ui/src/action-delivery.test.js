import test from 'node:test';
import assert from 'node:assert/strict';
import { actionSession, sendAction } from './commerce-actions.js';

const decode = message => JSON.parse(message.content[0].text.split('\n').at(-1));
test('old card selection survives absent or unrelated host context', async () => {
  const sent = [];
  const host = { sendMessage: async message => { sent.push(message); return {}; },
    updateModelContext: () => assert.fail('must not depend on model context') };
  const old = { tool: 'get_product_detail', args: { merchantId: 'merchant-c', merchantProductId: 'onion-1' } };
  await sendAction(host, old, fn => fn());
  await sendAction(host, { tool: 'search_products', args: { query: 'no matches' } }, fn => fn());
  await sendAction(host, old, fn => fn());
  assert.deepEqual(decode(sent[2]), { tool: old.tool, arguments: old.args });
  assert.deepEqual(sent[0], sent[2]);
});
test('write delivery preserves exact persisted arguments and never retries a failure', async () => {
  let saved, calls = 0;
  const session = actionSession(null, value => { saved = structuredClone(value); },
    () => '00000000-0000-4000-8000-000000000001');
  const intent = session.prepare('add_cart_item', { merchantId: 'merchant-a', merchantProductId: 'p', quantity: 2 });
  const host = { sendMessage: async message => {
    calls++; assert.deepEqual(decode(message), { tool: saved.tool, arguments: saved.args });
    throw new Error('delivery unavailable');
  } };
  await assert.rejects(sendAction(host, intent, fn => fn()), /delivery unavailable/);
  assert.equal(calls, 1);
  assert.deepEqual(session.pending, saved);
});
