import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { confirmLivePayment } from '../src/robokassa-state.mjs';

const ENDPOINT = 'https://auth.robokassa.ru/Merchant/WebService/Service.asmx/OpStateExt';
const order = Object.freeze({
  invoice_id: 127,
  merchant_login: 'diary-shop',
  amount: '149.00', currency: 'RUB', mode: 'live', store: 'rustore', status: 'pending',
  license_id: '22e52906-de33-4f76-889a-b9551b9e1114',
});
const env = Object.freeze({
  ROBOKASSA_MERCHANT_LOGIN: 'diary-shop',
  ROBOKASSA_PASSWORD2: 'a-live-password-2',
  ROBOKASSA_TEST_PASSWORD2: 'a-separate-test-password-2',
});
const xml = '<?xml version="1.0" encoding="utf-8"?>\n'
  + '<OperationStateResponse xmlns="http://merchant.roboxchange.com/WebService/" '
  + 'xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">\n'
  + '<Result><Code>0</Code></Result>\n'
  + '<State><Code>100</Code><RequestDate>2026-10-17T14:30:00+03:00</RequestDate>'
  + '<StateDate>2026-10-17T14:29:55+03:00</StateDate></State>\n'
  + '<Info><IncCurrLabel>BankCardPSR</IncCurrLabel><IncSum>155.00</IncSum>'
  + '<PaymentMethod><Code>BankCard</Code></PaymentMethod><OutCurrLabel>RUB</OutCurrLabel>'
  + '<OutSum>149.000000</OutSum><OpKey>provider-operation-key</OpKey></Info>\n'
  + '<UserFields>\n'
  + '<Field><Name>Shp_license</Name><Value>' + order.license_id + '</Value></Field>\n'
  + '<Field><Name>Shp_mode</Name><Value>live</Value></Field>\n'
  + '<Field><Name>Shp_product</Name><Value>ad_free_lifetime</Value></Field>\n'
  + '<Field><Name>Shp_store</Name><Value>rustore</Value></Field>\n'
  + '</UserFields>\n</OperationStateResponse>';

function response(body = xml, init = {}) {
  return new Response(body, { headers: { 'Content-Type': 'text/xml; charset=utf-8' }, ...init });
}

test('signed POST confirms the exact live order and never puts credentials in its URL', async t => {
  let request;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    request = { url, options };
    return response();
  });
  assert.equal(await confirmLivePayment(order, env), true);
  assert.equal(request.url, ENDPOINT);
  assert.equal(request.options.method, 'POST');
  assert.equal(request.options.redirect, 'error');
  assert.equal(request.options.credentials, 'omit');
  assert.equal(request.options.cache, 'no-store');
  assert.ok(request.options.signal instanceof AbortSignal);
  const params = new URLSearchParams(request.options.body);
  assert.deepEqual([...params.keys()], ['MerchantLogin', 'InvoiceID', 'Signature']);
  assert.equal(params.get('MerchantLogin'), order.merchant_login);
  assert.equal(params.get('InvoiceID'), '127');
  assert.equal(params.get('Signature'), createHash('sha256')
    .update('diary-shop:127:a-live-password-2').digest('hex'));
  assert.ok(!request.options.body.includes(env.ROBOKASSA_PASSWORD2));
});

test('existing live orders remain recoverable after checkout is disabled or mode switched', async t => {
  t.mock.method(globalThis, 'fetch', async () => response());
  assert.equal(await confirmLivePayment(order, { ...env, ROBOKASSA_MODE: 'test', PAYMENTS_ENABLED: 'false' }), true);
});

test('accepts decimal-equivalent OutSum and the legacy ruble code', async t => {
  t.mock.method(globalThis, 'fetch', async () => response(xml
    .replace('<OutSum>149.000000</OutSum>', '<OutSum>149</OutSum>')
    .replace('<OutCurrLabel>RUB</OutCurrLabel>', '<OutCurrLabel>RUR</OutCurrLabel>')));
  assert.equal(await confirmLivePayment(order, env), true);
});

for (const [label, changedOrder, changedEnv] of [
  ['test order', { mode: 'test' }, {}],
  ['unrelated merchant', { merchant_login: 'other-shop' }, {}],
  ['invalid invoice', { invoice_id: '127:evil' }, {}],
  ['unsafe invoice integer', { invoice_id: '9007199254740992' }, {}],
  ['zero invoice', { invoice_id: 0 }, {}],
  ['changed amount', { amount: '1.00' }, {}],
  ['changed currency', { currency: 'USD' }, {}],
  ['unknown store', { store: 'other' }, {}],
  ['invalid licence', { license_id: '' }, {}],
  ['different product', { product: 'another_product' }, {}],
  ['missing live password', {}, { ROBOKASSA_PASSWORD2: undefined }],
  ['same test and live password', {}, { ROBOKASSA_TEST_PASSWORD2: env.ROBOKASSA_PASSWORD2 }],
]) {
  test('does not query Robokassa for ' + label, async t => {
    const fetch = t.mock.method(globalThis, 'fetch', async () => { throw new Error('must not fetch'); });
    assert.equal(await confirmLivePayment({ ...order, ...changedOrder }, { ...env, ...changedEnv }), false);
    assert.equal(fetch.mock.callCount(), 0);
  });
}

for (const [label, changedXml] of [
  ['provider signature rejection', xml.replace('<Result><Code>0</Code>', '<Result><Code>1</Code>')],
  ['ambiguous invoice', xml.replace('<Result><Code>0</Code>', '<Result><Code>4</Code>')],
  ['received but not settled', xml.replace('<State><Code>100</Code>', '<State><Code>50</Code>')],
  ['cancelled', xml.replace('<State><Code>100</Code>', '<State><Code>10</Code>')],
  ['missing settlement amount', xml.replace('<OutSum>149.000000</OutSum>', '')],
  ['wrong amount although IncSum matches', xml.replace('<OutSum>149.000000</OutSum>', '<OutSum>1.00</OutSum>')
    .replace('<IncSum>155.00</IncSum>', '<IncSum>149.00</IncSum>')],
  ['fractional kopeck', xml.replace('<OutSum>149.000000</OutSum>', '<OutSum>149.000001</OutSum>')],
  ['exponent amount', xml.replace('<OutSum>149.000000</OutSum>', '<OutSum>1.49e2</OutSum>')],
  ['non-ruble currency', xml.replace('<OutCurrLabel>RUB</OutCurrLabel>', '<OutCurrLabel>USD</OutCurrLabel>')],
  ['missing currency', xml.replace('<OutCurrLabel>RUB</OutCurrLabel>', '')],
  ['duplicate settlement amount', xml.replace('<OutSum>149.000000</OutSum>', '<OutSum>149.00</OutSum><OutSum>149.00</OutSum>')],
  ['duplicate state', xml.replace('</State>', '</State><State><Code>100</Code></State>')],
  ['duplicate result code', xml.replace('<Result><Code>0</Code>', '<Result><Code>0</Code><Code>0</Code>')],
  ['other licence', xml.replace(order.license_id, 'aba57bb3-0a37-4c17-8b2c-a34a9032e993')],
  ['test licence field', xml.replace('<Value>live</Value>', '<Value>test</Value>')],
  ['other product', xml.replace('<Value>ad_free_lifetime</Value>', '<Value>another_product</Value>')],
  ['other store', xml.replace('<Value>rustore</Value>', '<Value>huawei</Value>')],
  ['missing field', xml.replace('<Field><Name>Shp_store</Name><Value>rustore</Value></Field>', '')],
  ['extra field', xml.replace('</UserFields>', '<Field><Name>Shp_other</Name><Value>1</Value></Field></UserFields>')],
  ['duplicate field replacing another', xml.replace('<Name>Shp_mode</Name><Value>live</Value>', '<Name>Shp_store</Name><Value>rustore</Value>')],
  ['case changed field', xml.replace('<Name>Shp_mode</Name>', '<Name>shp_mode</Name>')],
  ['missing UserFields', xml.slice(0, xml.indexOf('<UserFields>')) + '</OperationStateResponse>'],
  ['duplicate UserFields', xml.replace('</OperationStateResponse>', '<UserFields/></OperationStateResponse>')],
  ['wrong echoed merchant', xml.replace('<State>', '<State><MerchantLogin>other-shop</MerchantLogin>')],
  ['wrong echoed invoice', xml.replace('<Info>', '<Info><InvoiceID>128</InvoiceID>')],
  ['missing namespace', xml.replace('xmlns="http://merchant.roboxchange.com/WebService/"', '')],
  ['foreign namespace', xml.replace('http://merchant.roboxchange.com/WebService/', 'https://attacker.invalid/')],
  ['foreign child namespace', xml.replace('<State>', '<State xmlns="https://attacker.invalid/">')],
  ['malformed XML', xml.replace('</Info>', '</State>')],
  ['nested scalar', xml.replace('<OutSum>149.000000</OutSum>', '<OutSum><Value>149.00</Value></OutSum>')],
  ['DTD/entity declaration', '<!DOCTYPE x [<!ENTITY amount "149.000000">]>' + xml.replace('149.000000', '&amount;')],
  ['entity inside a required value', xml.replace('<Value>live</Value>', '<Value>li&#118;e</Value>')],
]) {
  test('fails closed on ' + label, async t => {
    t.mock.method(globalThis, 'fetch', async () => response(changedXml));
    assert.equal(await confirmLivePayment(order, env), false);
  });
}

test('checks echoed merchant and invoice when present', async t => {
  t.mock.method(globalThis, 'fetch', async () => response(xml.replace('<Info>',
    '<Info><MerchantLogin>diary-shop</MerchantLogin><InvoiceID>127</InvoiceID>')));
  assert.equal(await confirmLivePayment(order, env), true);
});

for (const [label, makeResponse] of [
  ['HTTP error', () => response(xml, { status: 503 })],
  ['HTTP redirect', () => response(xml, { status: 302, headers: { Location: 'https://example.com/' } })],
  ['non-XML content', () => response(xml, { headers: { 'Content-Type': 'text/html' } })],
  ['declared excessive body', () => response(xml, { headers: { 'Content-Type': 'text/xml', 'Content-Length': '999999' } })],
  ['streamed excessive body', () => response(xml + ' '.repeat(65536))],
  ['invalid UTF-8', () => response(new Uint8Array([0xc3, 0x28]))],
]) {
  test('fails closed on ' + label, async t => {
    t.mock.method(globalThis, 'fetch', async () => makeResponse());
    assert.equal(await confirmLivePayment(order, env), false);
  });
}

test('network failure is a false result, not an unhandled error', async t => {
  t.mock.method(globalThis, 'fetch', async () => { throw new TypeError('fetch failed'); });
  assert.equal(await confirmLivePayment(order, env), false);
});

test('deadline includes a stalled response body and aborts the HTTP request', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let signal;
  let headersDelivered;
  const started = new Promise(resolve => { headersDelivered = resolve; });
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    signal = options.signal;
    headersDelivered();
    return response(new ReadableStream({ start() {} }));
  });
  const check = confirmLivePayment(order, env);
  await started;
  t.mock.timers.tick(8000);
  assert.equal(await check, false);
  assert.equal(signal.aborted, true);
});
