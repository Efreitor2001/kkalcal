/**
 * Robokassa checkout + verified ResultURL. Runs on Cloudflare Workers + D1.
 * Passwords and the RSA private key exist only in Worker secrets.
 * SuccessURL, SDK callbacks, and client supplied booleans never grant a licence.
 */
import { confirmLivePayment } from './robokassa-state.mjs';

const CHECKOUT = 'https://auth.robokassa.ru/Merchant/Index.aspx';
export const PRODUCT = 'ad_free_lifetime';
export const AMOUNT = '149.00';
const CURRENCY = 'RUB';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const CLIENT_TOKEN = /^[A-Za-z0-9_-]{43}$/;
const INVOICE = /^[1-9][0-9]{0,15}$/;
const STORES = new Set(['googlePlay', 'rustore', 'huawei']);
const encoder = new TextEncoder();
const signingKeys = new Map();
const STATE_CHECK_INTERVAL_MS = 60_000;
const SECURITY_HEADERS = {
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'Strict-Transport-Security': 'max-age=31536000',
};

class ApiError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}

const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { ...SECURITY_HEADERS, 'Content-Type': 'application/json; charset=utf-8' },
});
const plain = (value, status = 200) => new Response(value, {
  status, headers: { ...SECURITY_HEADERS, 'Content-Type': 'text/plain; charset=utf-8' },
});

export async function sha256(value) {
  const bytes = await crypto.subtle.digest('SHA-256', encoder.encode(value));
  return Array.from(new Uint8Array(bytes), n => n.toString(16).padStart(2, '0')).join('');
}

export function constantTimeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export function amountKopecks(value) {
  if (typeof value !== 'string' || !/^[0-9]{1,10}(?:\.[0-9]{1,6})?$/.test(value)) return null;
  const [whole, fraction = ''] = value.split('.');
  if (fraction.slice(2).replaceAll('0', '') !== '') return null;
  return BigInt(whole) * 100n + BigInt((fraction + '00').slice(0, 2));
}

function origin(value) {
  try {
    const u = new URL(value);
    if (u.protocol !== 'https:' || u.username || u.password || u.search || u.hash || u.pathname !== '/') return null;
    return u.origin;
  } catch { return null; }
}

function credentials(env, mode) {
  return mode === 'test'
    ? [env.ROBOKASSA_TEST_PASSWORD1, env.ROBOKASSA_TEST_PASSWORD2]
    : [env.ROBOKASSA_PASSWORD1, env.ROBOKASSA_PASSWORD2];
}

function independentCredentials(env) {
  return !(env.ROBOKASSA_PASSWORD1 && env.ROBOKASSA_PASSWORD1 === env.ROBOKASSA_TEST_PASSWORD1)
    && !(env.ROBOKASSA_PASSWORD2 && env.ROBOKASSA_PASSWORD2 === env.ROBOKASSA_TEST_PASSWORD2);
}

export function availability(request, env, store) {
  const mode = env.ROBOKASSA_MODE;
  let reason = '';
  if (!STORES.has(store)) reason = 'unsupported_store';
  else if (env.PAYMENTS_ENABLED !== 'true') reason = 'not_available';
  else if (!['test', 'live'].includes(mode) || !origin(env.PUBLIC_BASE_URL)
    || !/^[A-Za-z0-9_.-]{1,100}$/.test(env.ROBOKASSA_MERCHANT_LOGIN || '')
    || !credentials(env, mode).every(x => typeof x === 'string' && x.length >= 8)
    || !independentCredentials(env) || !env.LICENSE_PRIVATE_KEY || !env.DB || !env.ORDER_RATE_LIMITER) reason = 'not_configured';
  // Use trusted platform metadata, never a caller-provided header or phone language.
  else if (store === 'googlePlay' && request.cf?.country !== 'RU') reason = 'region_unavailable';
  return { available: reason === '', reason, mode: mode === 'live' ? 'live' : 'test',
    product: PRODUCT, amount: AMOUNT, currency: CURRENCY };
}

async function signingMaterial(env) {
  const encoded = (env.LICENSE_PRIVATE_KEY || '').replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  if (signingKeys.has(encoded)) return signingKeys.get(encoded);
  const der = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, true, ['sign']);
  if (key.algorithm.modulusLength < 2048 || key.algorithm.modulusLength > 8192) {
    throw new ApiError(503, 'not_configured');
  }
  const { n, e } = await crypto.subtle.exportKey('jwk', key);
  const publicKey = await crypto.subtle.importKey('jwk', { kty: 'RSA', n, e, ext: true },
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, true, ['verify']);
  const publicDer = await crypto.subtle.exportKey('spki', publicKey);
  const hash = await crypto.subtle.digest('SHA-256', publicDer);
  const publicKeySha256 = Array.from(new Uint8Array(hash), x => x.toString(16).padStart(2, '0')).join('');
  const result = { key, publicKeySha256 };
  if (signingKeys.size >= 2) signingKeys.clear();
  signingKeys.set(encoded, result);
  return result;
}

async function readyConfig(request, env, store) {
  const config = availability(request, env, store);
  if (!config.available) return config;
  try {
    const { publicKeySha256 } = await signingMaterial(env);
    return { ...config, publicKeySha256 };
  } catch {
    return { ...config, available: false, reason: 'not_configured' };
  }
}

async function readBody(request, limit) {
  if (Number(request.headers.get('Content-Length') || 0) > limit) throw new ApiError(413, 'body_too_large');
  if (!request.body) return '';
  const reader = request.body.getReader();
  const parts = [];
  let size = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > limit) { await reader.cancel(); throw new ApiError(413, 'body_too_large'); }
    parts.push(value);
  }
  const bytes = new Uint8Array(size);
  let position = 0;
  for (const part of parts) { bytes.set(part, position); position += part.length; }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

function customFields(order) {
  return {
    Shp_license: order.license_id,
    Shp_mode: order.mode,
    Shp_product: PRODUCT,
    Shp_store: order.store,
  };
}

function customSignature(fields) {
  return Object.keys(fields).sort().map(key => `${key}=${fields[key]}`).join(':');
}

// Robokassa signs the URL-encoded Receipt; transport encoding is a separate step.
export function fiscalReceipt() {
  return encodeURIComponent(JSON.stringify({ items: [{
    name: 'Лицензия Дневник диеты - отключение рекламы',
    quantity: 1, sum: 149, tax: 'none',
    payment_method: 'full_payment', payment_object: 'intellectual_activity',
  }] }));
}

export async function checkoutUrl(order, env) {
  const [password] = credentials(env, order.mode);
  if (!password || order.merchant_login !== env.ROBOKASSA_MERCHANT_LOGIN) {
    throw new ApiError(503, 'not_configured');
  }
  const receipt = fiscalReceipt();
  const fields = customFields(order);
  const signature = await sha256([
    order.merchant_login, AMOUNT, String(order.invoice_id), receipt, password,
    customSignature(fields),
  ].join(':'));
  const url = new URL(CHECKOUT);
  url.search = new URLSearchParams({
    MerchantLogin: order.merchant_login,
    OutSum: AMOUNT,
    InvId: String(order.invoice_id),
    Description: 'Дневник диеты - отключение рекламы навсегда',
    SignatureValue: signature,
    Receipt: receipt,
    Culture: 'ru',
    Encoding: 'utf-8',
    IsTest: order.mode === 'test' ? '1' : '0',
    ...fields,
  }).toString();
  return url.href;
}

function base64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes)))
    .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

export async function signedReceipt(order, env) {
  if (order.mode !== 'live' || order.status !== 'paid' || !order.paid_at) {
    throw new ApiError(409, 'not_paid');
  }
  const { key } = await signingMaterial(env);
  const payload = base64url(encoder.encode(JSON.stringify({
    version: 1, product: PRODUCT, invoiceId: String(order.invoice_id),
    amount: AMOUNT, currency: CURRENCY, purchasedAt: order.paid_at,
    mode: 'live', licenseId: order.license_id,
  })));
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encoder.encode(payload));
  return `${payload}.${base64url(signature)}`;
}

async function orderResponse(order, env, request) {
  let receipt = order.receipt;
  if (order.status === 'paid' && !receipt) {
    receipt = await signedReceipt(order, env);
    // A repeat callback/GET must keep the original, equally valid receipt.
    await env.DB.prepare('UPDATE orders SET receipt = COALESCE(receipt, ?) WHERE invoice_id = ?')
      .bind(receipt, order.invoice_id).run();
  }
  const base = origin(env.PUBLIC_BASE_URL);
  const mayCheckout = order.status === 'pending' && order.mode === env.ROBOKASSA_MODE
    && (await readyConfig(request, env, order.store)).available;
  const checkout = mayCheckout ? new URL(await checkoutUrl(order, env)) : null;
  return {
    invoiceId: String(order.invoice_id), status: order.status, mode: order.mode,
    amount: AMOUNT, currency: CURRENCY,
    checkoutAvailable: !!checkout,
    ...(receipt ? { receipt } : {}),
    ...(checkout ? {
      checkoutUrl: CHECKOUT,
      postParams: checkout.search.slice(1),
      successUrl: `${base}/robokassa/success`, failUrl: `${base}/robokassa/fail`,
    } : {}),
  };
}

async function createOrder(request, env) {
  if (!env.DB) throw new ApiError(503, 'not_configured');
  if (!(request.headers.get('Content-Type') || '').toLowerCase().startsWith('application/json')) {
    throw new ApiError(415, 'json_required');
  }
  let body;
  try { body = JSON.parse(await readBody(request, 2048)); }
  catch (e) { if (e instanceof ApiError) throw e; throw new ApiError(400, 'invalid_request'); }
  if (!body || Object.keys(body).some(k => !['store', 'idempotencyKey', 'clientToken'].includes(k))
    || !STORES.has(body.store) || !UUID.test(body.idempotencyKey || '') || !CLIENT_TOKEN.test(body.clientToken || '')) {
    throw new ApiError(400, 'invalid_request');
  }
  const hash = await sha256(body.clientToken);
  const previous = await env.DB.prepare('SELECT * FROM orders WHERE idempotency_key = ?')
    .bind(body.idempotencyKey).first();
  if (previous) {
    if (!constantTimeEqual(hash, previous.token_hash) || body.store !== previous.store) throw new ApiError(409, 'idempotency_conflict');
    return json(await orderResponse(previous, env, request));
  }
  const config = await readyConfig(request, env, body.store);
  if (!config.available) throw new ApiError(config.reason === 'region_unavailable' ? 403 : 503, config.reason);
  const ip = request.headers.get('CF-Connecting-IP');
  if (!ip) throw new ApiError(503, 'trusted_network_required');
  const { success } = await env.ORDER_RATE_LIMITER.limit({ key: await sha256(`order:${ip}`) });
  if (!success) throw new ApiError(429, 'try_later');
  // UNIQUE idempotency_key makes simultaneous retries safe, without a read/write race.
  await env.DB.prepare(`INSERT INTO orders
    (idempotency_key, token_hash, store, mode, merchant_login, amount, currency, license_id, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(idempotency_key) DO NOTHING`)
    .bind(body.idempotencyKey, hash, body.store, config.mode, env.ROBOKASSA_MERCHANT_LOGIN,
      AMOUNT, CURRENCY, crypto.randomUUID(), Date.now()).run();
  const order = await env.DB.prepare('SELECT * FROM orders WHERE idempotency_key = ?').bind(body.idempotencyKey).first();
  if (!order || !constantTimeEqual(hash, order.token_hash) || body.store !== order.store) throw new ApiError(409, 'idempotency_conflict');
  return json(await orderResponse(order, env, request), 201);
}

async function getOrder(request, env, id) {
  if (!INVOICE.test(id) || !Number.isSafeInteger(Number(id))) throw new ApiError(404, 'not_found');
  const token = request.headers.get('Authorization')?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
  if (!token) throw new ApiError(401, 'unauthorized');
  if (!env.DB) throw new ApiError(503, 'not_configured');
  const tokenHash = await sha256(token);
  let order = await env.DB.prepare('SELECT * FROM orders WHERE invoice_id = ?').bind(Number(id)).first();
  if (!order || !constantTimeEqual(tokenHash, order.token_hash)) throw new ApiError(404, 'not_found');
  if (order.status === 'pending' && order.mode === 'live') {
    const checkedAt = Date.now();
    // Authentication above precedes every provider request. A conditional UPDATE
    // reserves this invoice's check on D1 itself, so concurrent Worker instances
    // cannot all poll Robokassa. A failed exchange still consumes this interval.
    const claim = await env.DB.prepare(`UPDATE orders SET last_state_check = ?
      WHERE invoice_id = ? AND status = 'pending' AND mode = 'live'
        AND (last_state_check IS NULL OR last_state_check <= ?)`)
      .bind(checkedAt, order.invoice_id, checkedAt - STATE_CHECK_INTERVAL_MS).run();
    if (claim.success === true && claim.meta?.changes === 1 && await confirmLivePayment(order, env)) {
      // ResultURL may win while OpStateExt is in flight. Never overwrite its
      // state, purchase timestamp or receipt, and never manufacture a test licence.
      await env.DB.prepare(`UPDATE orders SET status = 'paid', paid_at = COALESCE(paid_at, ?)
        WHERE invoice_id = ? AND status = 'pending' AND mode = 'live'
          AND merchant_login = ? AND license_id = ?`)
        .bind(Date.now(), order.invoice_id, order.merchant_login, order.license_id).run();
    }
    // Read the persisted outcome, including a callback or another check that won
    // the race. Only orderResponse below is responsible for issuing the proof.
    order = await env.DB.prepare('SELECT * FROM orders WHERE invoice_id = ?').bind(Number(id)).first();
    if (!order || !constantTimeEqual(tokenHash, order.token_hash)) throw new ApiError(404, 'not_found');
  }
  return json(await orderResponse(order, env, request));
}

async function resultCallback(request, env) {
  if (!env.DB) throw new ApiError(503, 'not_configured');
  if (!(request.headers.get('Content-Type') || '').toLowerCase().startsWith('application/x-www-form-urlencoded')) {
    throw new ApiError(415, 'form_required');
  }
  const form = new URLSearchParams(await readBody(request, 16384));
  const seen = new Set();
  for (const [key] of form) {
    if (seen.has(key)) throw new ApiError(400, 'duplicate_parameter');
    seen.add(key);
  }
  const id = form.get('InvId');
  const sum = form.get('OutSum');
  const signature = form.get('SignatureValue') || '';
  if (!INVOICE.test(id || '') || !Number.isSafeInteger(Number(id)) || !/^[0-9a-f]{64}$/i.test(signature)
    || amountKopecks(sum) !== 14900n) throw new ApiError(400, 'invalid_payment');
  const order = await env.DB.prepare('SELECT * FROM orders WHERE invoice_id = ?').bind(Number(id)).first();
  if (!order || order.merchant_login !== env.ROBOKASSA_MERCHANT_LOGIN) throw new ApiError(400, 'invalid_payment');
  const expectedFields = customFields(order);
  const actualKeys = [...seen].filter(k => /^shp_/i.test(k)).sort();
  if (actualKeys.join(',') !== Object.keys(expectedFields).sort().join(',')
    || actualKeys.some(k => form.get(k) !== expectedFields[k])) throw new ApiError(400, 'invalid_payment');
  const [, password2] = credentials(env, order.mode);
  if (!password2 || !independentCredentials(env)) throw new ApiError(503, 'not_configured');
  const expected = await sha256([sum, id, password2, customSignature(expectedFields)].join(':'));
  if (!constantTimeEqual(expected, signature.toLowerCase())) throw new ApiError(400, 'invalid_signature');
  const status = order.mode === 'live' ? 'paid' : 'test_paid';
  await env.DB.prepare(`UPDATE orders SET status = ?, paid_at = COALESCE(paid_at, ?)
    WHERE invoice_id = ? AND status = 'pending'`).bind(status, Date.now(), order.invoice_id).run();
  // Do not sign here: a temporary key-service failure must not lose the payment.
  // An authenticated GET signs an already verified live order, with safe retries.
  return plain(`OK${id}`);
}

function returnPage(success) {
  const title = success ? 'Платёж обрабатывается' : 'Возврат в приложение';
  const message = success
    ? 'Вернитесь в Дневник диеты и нажмите «Проверить оплату». Приложение подтвердит покупку после ответа Робокассы.'
    : 'Вернитесь в Дневник диеты. Если деньги были списаны, нажмите «Проверить оплату».';
  return new Response(`<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${title}</title><main><h1>${title}</h1><p>${message}</p></main></html>`, {
    headers: { ...SECURITY_HEADERS, 'Content-Type': 'text/html; charset=utf-8',
      'Content-Security-Policy': "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'" },
  });
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (url.protocol !== 'https:') throw new ApiError(400, 'https_required');
      if (request.method === 'GET' && url.pathname === '/health') return json({ ok: true, service: 'diet-diary-payments' });
      if (request.method === 'GET' && url.pathname === '/v1/config') return json(await readyConfig(request, env, url.searchParams.get('store')));
      if (request.method === 'POST' && url.pathname === '/v1/orders') return await createOrder(request, env);
      const match = url.pathname.match(/^\/v1\/orders\/([^/]+)$/);
      if (request.method === 'GET' && match) return await getOrder(request, env, match[1]);
      if (request.method === 'POST' && url.pathname === '/robokassa/result') return await resultCallback(request, env);
      if (request.method === 'GET' && url.pathname === '/robokassa/success') return returnPage(true);
      if (request.method === 'GET' && url.pathname === '/robokassa/fail') return returnPage(false);
      throw new ApiError(404, 'not_found');
    } catch (error) {
      // Never return/log request bodies, checkout tokens, passwords or raw provider errors.
      return json({ error: error instanceof ApiError ? error.code : 'temporarily_unavailable' },
        error instanceof ApiError ? error.status : 503);
    }
  },
};
