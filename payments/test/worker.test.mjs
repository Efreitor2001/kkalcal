import { afterEach, beforeEach, mock as network, test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { generateKeyPairSync, verify, createPublicKey, createHash } from 'node:crypto';
import worker, { availability, amountKopecks, sha256, fiscalReceipt } from '../src/worker.mjs';

// Every test is offline, including older callback tests that read a pending
// live order. Individual fallback tests install explicit provider fixtures.
let providerFetch;
beforeEach(() => {
  providerFetch = network.method(globalThis, 'fetch', async () => {
    throw new TypeError('External network is disabled in payment tests');
  });
});
afterEach(() => network.reset());

const { privateKey, publicKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicKeyEncoding: { type: 'spki', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
});

class D1 {
  constructor() {
    this.db = new DatabaseSync(':memory:');
    this.db.exec(readFileSync(new URL('../migrations/0001_orders.sql', import.meta.url), 'utf8'));
  }
  prepare(sql) {
    const statement = this.db.prepare(sql);
    return { bind: (...args) => ({
      first: async () => statement.get(...args) || null,
      run: async () => {
        const result = statement.run(...args);
        // Match the real D1Result shape, not node:sqlite's flat changes field.
        return { success: true, meta: { changes: result.changes, last_row_id: result.lastInsertRowid }, results: [] };
      },
    }) };
  }
}

function environment(overrides = {}) {
  return {
    DB: new D1(), PAYMENTS_ENABLED: 'true', ROBOKASSA_MODE: 'live',
    PUBLIC_BASE_URL: 'https://payments.example.com', ROBOKASSA_MERCHANT_LOGIN: 'diet-diary-test',
    ROBOKASSA_PASSWORD1: 'example-live-password-one', ROBOKASSA_PASSWORD2: 'example-live-password-two',
    ROBOKASSA_TEST_PASSWORD1: 'example-test-password-one', ROBOKASSA_TEST_PASSWORD2: 'example-test-password-two',
    LICENSE_PRIVATE_KEY: privateKey,
    ORDER_RATE_LIMITER: { limit: async () => ({ success: true }) },
    ...overrides,
  };
}

function request(path, options = {}, country = 'RU') {
  const result = new Request('https://payments.example.com' + path, options);
  Object.defineProperty(result, 'cf', { value: { country } });
  return result;
}

function orderBody(store = 'rustore') {
  return { store, idempotencyKey: crypto.randomUUID(),
    clientToken: Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url') };
}

async function create(env, body = orderBody(), country = 'RU') {
  const response = await worker.fetch(request('/v1/orders', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '192.0.2.10' },
    body: JSON.stringify(body),
  }, country), env);
  return { response, data: await response.json(), body };
}

async function status(env, order, token = order.body.clientToken) {
  const response = await worker.fetch(request('/v1/orders/' + order.data.invoiceId, {
    headers: { Authorization: 'Bearer ' + token },
  }, 'US'), env);
  return { response, data: await response.json() };
}

async function callback(env, order, overrides = {}, secret) {
  const checkout = new URLSearchParams(order.data.postParams);
  const custom = Object.fromEntries([...checkout].filter(([key]) => key.startsWith('Shp_')));
  const fields = { OutSum: '149.000000', InvId: order.data.invoiceId, ...custom, ...overrides };
  const pass = secret || (order.data.mode === 'test' ? env.ROBOKASSA_TEST_PASSWORD2 : env.ROBOKASSA_PASSWORD2);
  const values = Object.keys(fields).filter(k => k.startsWith('Shp_')).sort().map(k => `${k}=${fields[k]}`);
  if (!fields.SignatureValue) fields.SignatureValue = await sha256([fields.OutSum, fields.InvId, pass, ...values].join(':'));
  return worker.fetch(request('/robokassa/result', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields),
  }), env);
}

function storedOrder(env, order) {
  return env.DB.db.prepare('SELECT * FROM orders WHERE invoice_id = ?').get(Number(order.data.invoiceId));
}

function providerState(order, { code = '100', amount = '149.000000', licence } = {}) {
  const params = new URLSearchParams(order.data.postParams);
  const fields = [...params].filter(([key]) => key.startsWith('Shp_'))
    .map(([name, value]) => '<Field><Name>' + name + '</Name><Value>'
      + (name === 'Shp_license' && licence !== undefined ? licence : value) + '</Value></Field>').join('');
  return new Response('<OperationStateResponse xmlns="http://merchant.roboxchange.com/WebService/">'
    + '<Result><Code>0</Code></Result><State><Code>' + code + '</Code></State>'
    + '<Info><OutCurrLabel>RUB</OutCurrLabel><OutSum>' + amount + '</OutSum></Info>'
    + '<UserFields>' + fields + '</UserFields></OperationStateResponse>',
  { headers: { 'Content-Type': 'text/xml; charset=utf-8' } });
}

function verifiedClaims(receipt) {
  const [payload, signature] = receipt.split('.');
  assert(verify('RSA-SHA256', Buffer.from(payload), publicKey, Buffer.from(signature, 'base64url')));
  return JSON.parse(Buffer.from(payload, 'base64url'));
}

test('live callback grants a verifiable, immutable licence; SDK redirect alone does not', async () => {
  const env = environment();
  const order = await create(env);
  assert.equal(order.response.status, 201);
  assert.equal(order.data.amount, '149.00');
  assert.equal(order.data.checkoutUrl, 'https://auth.robokassa.ru/Merchant/Index.aspx');
  assert.equal(order.data.successUrl, 'https://payments.example.com/robokassa/success');
  const redirect = await worker.fetch(request('/robokassa/success?InvId=' + order.data.invoiceId), env);
  assert.equal(redirect.status, 200);
  assert.equal((await status(env, order)).data.status, 'pending');
  const confirmation = await callback(env, order);
  assert.equal(confirmation.status, 200);
  assert.equal(await confirmation.text(), 'OK' + order.data.invoiceId);
  const paid = await status(env, order);
  assert.equal(paid.data.status, 'paid');
  const [payload, signature] = paid.data.receipt.split('.');
  assert(verify('RSA-SHA256', Buffer.from(payload), publicKey, Buffer.from(signature, 'base64url')));
  const claims = JSON.parse(Buffer.from(payload, 'base64url'));
  assert.equal(claims.mode, 'live');
  assert.equal(claims.product, 'ad_free_lifetime');
  assert.equal(claims.invoiceId, order.data.invoiceId);
  assert.equal(claims.amount, '149.00');
  assert.equal(claims.currency, 'RUB');
  assert(claims.purchasedAt > 0);
  await callback(env, order);
  assert.equal((await status(env, order)).data.receipt, paid.data.receipt);
});

test('test payment is recorded but can never issue a permanent licence, including after switching to live', async () => {
  const env = environment({ ROBOKASSA_MODE: 'test' });
  const order = await create(env);
  assert.equal(order.data.mode, 'test');
  env.ROBOKASSA_MODE = 'live';
  assert.equal((await callback(env, order)).status, 200);
  const paid = await status(env, order);
  assert.equal(paid.data.status, 'test_paid');
  assert.equal(paid.data.receipt, undefined);
});

test('wrong signature, wrong amount, wrong product, wrong mode and unknown invoice never activate', async () => {
  const env = environment();
  const order = await create(env);
  for (const override of [
    { SignatureValue: 'f'.repeat(64) }, { OutSum: '148.99' }, { OutSum: '149.001' },
    { Shp_mode: 'test' }, { Shp_product: 'another_product' },
    { Shp_store: 'huawei' }, { Shp_license: crypto.randomUUID() }, { Shp_unexpected: 'anything' },
    { InvId: '9000001' },
  ]) {
    assert.equal((await callback(env, order, override)).status, 400, JSON.stringify(override));
    assert.equal((await status(env, order)).data.status, 'pending');
  }
  assert.equal((await callback(env, order, {}, env.ROBOKASSA_PASSWORD1)).status, 400);
});

test('duplicate callback parameters are rejected rather than parsed ambiguously', async () => {
  const env = environment();
  const response = await worker.fetch(request('/robokassa/result', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'InvId=1&InvId=2&OutSum=149.00&SignatureValue=' + '0'.repeat(64),
  }), env);
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, 'duplicate_parameter');
});

test('idempotent retries survive lost response, disabled checkout and travel; token remains required', async () => {
  const env = environment();
  const body = orderBody('googlePlay');
  const first = await create(env, body);
  env.PAYMENTS_ENABLED = 'false';
  const retry = await create(env, body, 'US');
  assert.equal(retry.response.status, 200);
  assert.equal(retry.data.invoiceId, first.data.invoiceId);
  assert.equal(retry.data.checkoutAvailable, false);
  assert.equal(retry.data.postParams, undefined);
  const impostor = await create(env, { ...body, clientToken: 'a'.repeat(43) });
  assert.equal(impostor.response.status, 409);
  const anotherStore = await create(env, { ...body, store: 'huawei' });
  assert.equal(anotherStore.response.status, 409);
  assert.equal((await status(env, first, 'b'.repeat(43))).response.status, 404);
  assert.equal((await status(env, first)).response.status, 200);
  assert.equal((await create(env)).response.status, 503);
});

test('concurrent order retries create only one invoice', async () => {
  const env = environment();
  const body = orderBody();
  const results = await Promise.all(Array.from({ length: 6 }, () => create(env, body)));
  assert(results.every(r => [200, 201].includes(r.response.status)));
  assert.equal(new Set(results.map(r => r.data.invoiceId)).size, 1);
  assert.equal(env.DB.db.prepare('SELECT COUNT(*) AS n FROM orders').get().n, 1);
});

test('Google Play admission uses trusted RU country, never locale or spoofed CF-IPCountry header', async () => {
  const env = environment();
  assert.equal((await create(env, orderBody('googlePlay'), 'US')).response.status, 403);
  assert.equal((await create(env, orderBody('googlePlay'), undefined)).response.status, 201);
  const noCountry = request('/v1/config?store=googlePlay', { headers: { 'CF-IPCountry': 'RU' } }, 'XX');
  assert.equal(availability(noCountry, env, 'googlePlay').available, false);
  assert.equal(availability(new Request('https://example.com'), env, 'googlePlay').available, false);
  assert.equal(availability(request('/'), env, 'googlePlay').available, true);
  assert.equal((await create(env, orderBody('rustore'), 'US')).response.status, 201);
  assert.equal((await create(env, orderBody('huawei'), 'US')).response.status, 201);
});

test('checkout fixes price, signs encoded fiscal items and contains no merchant passwords or private key', async () => {
  const env = environment();
  const order = await create(env);
  const params = new URLSearchParams(order.data.postParams);
  assert.equal(params.get('OutSum'), '149.00');
  assert.equal(params.get('Receipt'), fiscalReceipt());
  const fiscal = JSON.parse(decodeURIComponent(params.get('Receipt')));
  assert.equal(fiscal.items[0].sum, 149);
  assert.equal(fiscal.items[0].tax, 'none');
  assert.equal(fiscal.sno, undefined); // NPD must not be mislabelled as USN/OSN.
  const custom = [...params].filter(([k]) => k.startsWith('Shp_')).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${k}=${v}`);
  const expected = await sha256([env.ROBOKASSA_MERCHANT_LOGIN, '149.00', order.data.invoiceId,
    params.get('Receipt'), env.ROBOKASSA_PASSWORD1, ...custom].join(':'));
  assert.equal(params.get('SignatureValue'), expected);
  for (const secret of [env.ROBOKASSA_PASSWORD1, env.ROBOKASSA_PASSWORD2, privateKey]) {
    assert(!JSON.stringify(order.data).includes(secret));
  }
  assert.equal((await create(env, { ...orderBody(), amount: '1.00' })).response.status, 400);
});

test('shared test/live passwords fail closed to prevent test checkout granting a live purchase', async () => {
  const env = environment();
  const order = await create(env);
  env.ROBOKASSA_TEST_PASSWORD2 = env.ROBOKASSA_PASSWORD2;
  assert.equal((await create(env)).response.status, 503);
  assert.equal((await callback(env, order)).status, 503);
});

test('malformed and weak private keys cannot advertise or create payable orders', async () => {
  const { privateKey: weak } = generateKeyPairSync('rsa', { modulusLength: 1024,
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' }, publicKeyEncoding: { type: 'spki', format: 'pem' } });
  for (const key of ['not-a-valid-private-key', weak]) {
    const env = environment({ LICENSE_PRIVATE_KEY: key });
    const config = await worker.fetch(request('/v1/config?store=rustore'), env);
    assert.equal((await config.json()).available, false);
    assert.equal((await create(env)).response.status, 503);
    assert.equal(env.DB.db.prepare('SELECT COUNT(*) AS n FROM orders').get().n, 0);
  }
});

test('config fingerprint belongs to actual signing key, allowing the APK to detect misconfiguration before payment', async () => {
  const env = environment();
  const config = await worker.fetch(request('/v1/config?store=rustore'), env);
  const der = createPublicKey(privateKey).export({ type: 'spki', format: 'der' });
  assert.equal((await config.json()).publicKeySha256, createHash('sha256').update(der).digest('hex'));
});

test('integer money parser accepts Robokassa 2/6 decimals and rejects precision, exponent and sign tricks', () => {
  for (const sum of ['149', '149.0', '149.00', '149.000000']) assert.equal(amountKopecks(sum), 14900n);
  for (const sum of ['149.000001', '1.49e2', '-149', '+149', '149,00', 'NaN', '149.0000000', null]) {
    assert.equal(amountKopecks(sum), null);
  }
});

test('limits and errors do not reveal sensitive data', async () => {
  const env = environment({ ORDER_RATE_LIMITER: { limit: async () => ({ success: false }) } });
  const limited = await create(env);
  assert.equal(limited.response.status, 429);
  const huge = await worker.fetch(request('/v1/orders', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: 'x'.repeat(3000),
  }), env);
  assert.equal(huge.status, 413);
  const badEnv = environment({ DB: { prepare: () => { throw new Error('SECRET_DATABASE_DETAIL'); } } });
  const failed = await create(badEnv);
  assert.deepEqual(failed.data, { error: 'temporarily_unavailable' });
  assert.equal(failed.response.headers.get('Cache-Control'), 'no-store');
});

test('pending recovery authenticates the token before claiming or contacting Robokassa', async () => {
  const env = environment();
  const order = await create(env);
  const missing = await worker.fetch(request('/v1/orders/' + order.data.invoiceId), env);
  assert.equal(missing.status, 401);
  assert.equal((await status(env, order, 'b'.repeat(43))).response.status, 404);
  const unknown = await worker.fetch(request('/v1/orders/999999', {
    headers: { Authorization: 'Bearer ' + order.body.clientToken },
  }), env);
  assert.equal(unknown.status, 404);
  const wrongRetry = await create(env, { ...order.body, clientToken: 'c'.repeat(43) });
  assert.equal(wrongRetry.response.status, 409);
  assert.equal(providerFetch.mock.callCount(), 0);
  assert.equal(storedOrder(env, order).last_state_check, null);
  assert.equal(storedOrder(env, order).status, 'pending');
});

test('verified live fallback persists paid and issues the same offline proof as ResultURL', async () => {
  const env = environment();
  const order = await create(env, orderBody('googlePlay'));
  providerFetch.mock.mockImplementation(async () => providerState(order));
  // Recovery is valid after travelling, disabling checkout or selecting test mode.
  env.PAYMENTS_ENABLED = 'false';
  env.ROBOKASSA_MODE = 'test';
  const paid = await status(env, order);
  assert.equal(paid.response.status, 200);
  assert.equal(paid.data.status, 'paid');
  assert.equal(paid.data.checkoutAvailable, false);
  const persisted = storedOrder(env, order);
  assert.equal(persisted.status, 'paid');
  assert.equal(persisted.receipt, paid.data.receipt);
  assert(persisted.last_state_check > 0);
  assert(persisted.paid_at >= persisted.last_state_check);
  const claims = verifiedClaims(paid.data.receipt);
  assert.equal(claims.invoiceId, order.data.invoiceId);
  assert.equal(claims.licenseId, persisted.license_id);
  assert.equal(claims.product, 'ad_free_lifetime');
  assert.equal(claims.mode, 'live');
  assert.equal(claims.amount, '149.00');
  assert.equal(claims.currency, 'RUB');
  assert.equal(claims.purchasedAt, persisted.paid_at);
  assert.equal(providerFetch.mock.callCount(), 1);
  assert.equal((await status(env, order)).data.receipt, paid.data.receipt);
  assert.equal(providerFetch.mock.callCount(), 1);
});

test('a failed provider exchange preserves pending and reserves one check per 60 seconds', async t => {
  let now = 1_800_000_000_000;
  t.mock.method(Date, 'now', () => now);
  const env = environment();
  const order = await create(env);
  const initial = storedOrder(env, order);
  assert.equal((await status(env, order)).data.status, 'pending');
  assert.equal(providerFetch.mock.callCount(), 1);
  const failed = storedOrder(env, order);
  assert.equal(failed.last_state_check, now);
  assert.equal(failed.token_hash, initial.token_hash);
  assert.equal(failed.license_id, initial.license_id);
  assert.equal(failed.paid_at, null);
  assert.equal(failed.receipt, null);
  now += 59_999;
  assert.equal((await status(env, order)).data.status, 'pending');
  assert.equal(providerFetch.mock.callCount(), 1);
  assert.equal(storedOrder(env, order).last_state_check, failed.last_state_check);
  now += 1;
  providerFetch.mock.mockImplementation(async () => providerState(order));
  assert.equal((await status(env, order)).data.status, 'paid');
  assert.equal(providerFetch.mock.callCount(), 2);
  assert.equal(storedOrder(env, order).last_state_check, now);
});

test('concurrent status requests claim only one provider check for an invoice', async () => {
  const env = environment();
  const order = await create(env);
  let release;
  let notifyStarted;
  const held = new Promise(resolve => { release = resolve; });
  const started = new Promise(resolve => { notifyStarted = resolve; });
  providerFetch.mock.mockImplementation(async () => {
    notifyStarted();
    await held;
    return providerState(order);
  });
  const owner = status(env, order);
  await started;
  const followers = await Promise.all(Array.from({ length: 6 }, () => status(env, order)));
  assert(followers.every(value => value.response.status === 200 && value.data.status === 'pending'));
  assert.equal(providerFetch.mock.callCount(), 1);
  release();
  const paid = await owner;
  assert.equal(paid.data.status, 'paid');
  assert.equal(verifiedClaims(paid.data.receipt).invoiceId, order.data.invoiceId);
  const repeat = await Promise.all(Array.from({ length: 3 }, () => status(env, order)));
  assert(repeat.every(value => value.data.receipt === paid.data.receipt));
  assert.equal(providerFetch.mock.callCount(), 1);
});

test('a callback winning during fallback preserves its timestamp and receipt', async t => {
  let now = 1_800_000_000_000;
  t.mock.method(Date, 'now', () => now);
  const env = environment();
  const order = await create(env);
  let release;
  let notifyStarted;
  const held = new Promise(resolve => { release = resolve; });
  const started = new Promise(resolve => { notifyStarted = resolve; });
  providerFetch.mock.mockImplementation(async () => {
    notifyStarted();
    await held;
    return providerState(order);
  });
  const checking = status(env, order);
  await started;
  now += 3000;
  assert.equal((await callback(env, order)).status, 200);
  const callbackPaid = await status(env, order);
  const original = storedOrder(env, order);
  now += 1000;
  release();
  const recovered = await checking;
  assert.equal(recovered.data.status, 'paid');
  assert.equal(recovered.data.receipt, callbackPaid.data.receipt);
  assert.equal(storedOrder(env, order).paid_at, original.paid_at);
  assert.equal(verifiedClaims(recovered.data.receipt).purchasedAt, original.paid_at);
  assert.equal(providerFetch.mock.callCount(), 1);
});

for (const [label, providerResult] of [
  ['unsettled payment', { code: '50' }],
  ['wrong amount', { amount: '148.99' }],
  ['another licence', { licence: '33e52906-de33-4f76-889a-b9551b9e1114' }],
]) {
  test('fallback leaves the order pending for ' + label, async () => {
    const env = environment();
    const order = await create(env);
    providerFetch.mock.mockImplementation(async () => providerState(order, providerResult));
    const pending = await status(env, order);
    assert.equal(pending.response.status, 200);
    assert.equal(pending.data.status, 'pending');
    assert.equal(pending.data.receipt, undefined);
    const saved = storedOrder(env, order);
    assert.equal(saved.status, 'pending');
    assert.equal(saved.paid_at, null);
    assert.equal(saved.receipt, null);
    assert(saved.last_state_check > 0);
  });
}

test('test pending, test paid and already paid live orders never invoke OpStateExt', async () => {
  const env = environment({ ROBOKASSA_MODE: 'test' });
  const sandbox = await create(env);
  assert.equal((await status(env, sandbox)).data.status, 'pending');
  assert.equal(storedOrder(env, sandbox).last_state_check, null);
  await callback(env, sandbox);
  assert.equal((await status(env, sandbox)).data.status, 'test_paid');
  assert.equal(storedOrder(env, sandbox).last_state_check, null);
  env.ROBOKASSA_MODE = 'live';
  const real = await create(env);
  await callback(env, real);
  assert.equal((await status(env, real)).data.status, 'paid');
  assert.equal(storedOrder(env, real).last_state_check, null);
  assert.equal(providerFetch.mock.callCount(), 0);
});

test('private keys above the Android 8192-bit limit cannot advertise or create a payable order', async t => {
  // Avoid generating a costly 16K key merely to test an imported key's bound.
  t.mock.method(crypto.subtle, 'importKey', async () => ({ algorithm: { modulusLength: 16_384 } }));
  const env = environment({ LICENSE_PRIVATE_KEY: 'AQ==' });
  const response = await worker.fetch(request('/v1/config?store=rustore'), env);
  assert.equal((await response.json()).available, false);
  assert.equal((await create(env)).response.status, 503);
  assert.equal(env.DB.db.prepare('SELECT COUNT(*) AS n FROM orders').get().n, 0);
});
