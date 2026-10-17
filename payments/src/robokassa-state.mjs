import { XMLParser, XMLValidator } from 'fast-xml-parser';

// Official contract: https://docs.robokassa.ru/ru/xml-interfaces
// This API supports LIVE operations only. ResultURL remains the primary path.
const ENDPOINT = 'https://auth.robokassa.ru/Merchant/WebService/Service.asmx/OpStateExt';
const NAMESPACE = 'http://merchant.roboxchange.com/WebService/';
const MAX_RESPONSE_BYTES = 64 * 1024;
const TIMEOUT_MS = 8000;
const PRODUCT = 'ad_free_lifetime';
const STORES = new Set(['googlePlay', 'rustore', 'huawei']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const encoder = new TextEncoder();

const parser = new XMLParser({
  ignoreAttributes: false,
  removeNSPrefix: false,
  parseTagValue: false,
  parseAttributeValue: false,
  trimValues: false,
  processEntities: false,
  ignoreDeclaration: true,
  maxNestedTags: 12,
});

function record(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function validOrder(order, env) {
  if (!record(order) || !record(env) || order.mode !== 'live'
    || order.amount !== '149.00' || order.currency !== 'RUB'
    || !STORES.has(order.store) || !UUID.test(order.license_id || '')
    || (order.product !== undefined && order.product !== PRODUCT)
    || !/^[A-Za-z0-9_.-]{1,100}$/.test(order.merchant_login || '')
    || order.merchant_login !== env.ROBOKASSA_MERCHANT_LOGIN
    || typeof env.ROBOKASSA_PASSWORD2 !== 'string'
    || env.ROBOKASSA_PASSWORD2.length < 8 || env.ROBOKASSA_PASSWORD2.length > 512
    || env.ROBOKASSA_PASSWORD2 === env.ROBOKASSA_TEST_PASSWORD2) return false;
  if (typeof order.invoice_id !== 'number' && typeof order.invoice_id !== 'string') return false;
  const invoice = String(order.invoice_id);
  return /^[1-9][0-9]{0,15}$/.test(invoice)
    && BigInt(invoice) <= BigInt(Number.MAX_SAFE_INTEGER);
}

function kopecks(value) {
  if (typeof value !== 'string' || !/^[0-9]{1,10}(?:\.[0-9]{1,6})?$/.test(value)) return null;
  const [whole, fraction = ''] = value.split('.');
  if (fraction.slice(2).replaceAll('0', '') !== '') return null;
  return BigInt(whole) * 100n + BigInt((fraction + '00').slice(0, 2));
}

function validNamespaces(value, isRoot = false) {
  if (Array.isArray(value)) return value.every(item => validNamespaces(item));
  if (!record(value)) return true;
  for (const [key, item] of Object.entries(value)) {
    if (key === '@_xmlns') {
      if (item !== NAMESPACE) return false;
    } else if (key.startsWith('@_xmlns:')) {
      const allowed = isRoot && (
        (key === '@_xmlns:xsd' && item === 'http://www.w3.org/2001/XMLSchema')
        || (key === '@_xmlns:xsi' && item === 'http://www.w3.org/2001/XMLSchema-instance'));
      if (!allowed) return false;
    } else if (!key.startsWith('@_') && key.includes(':')) {
      // Preserve namespaces; never collapse a foreign element into a trusted name.
      return false;
    } else if (!validNamespaces(item)) return false;
  }
  return true;
}

function onlyChildren(value, allowed) {
  if (!record(value)) return false;
  return Object.entries(value).every(([key, item]) => allowed.includes(key)
    || (key === '#text' && typeof item === 'string' && item.trim() === ''));
}

function echoedContextMatches(container, order) {
  // The documented response does NOT echo these fields. Its context is the
  // authenticated POST below. If a service version does echo them, reject a
  // mismatch instead of ignoring contradictory evidence.
  return (!Object.hasOwn(container, 'MerchantLogin')
      || container.MerchantLogin === order.merchant_login)
    && (!Object.hasOwn(container, 'InvoiceID')
      || container.InvoiceID === String(order.invoice_id));
}

function confirmsOrder(xml, order) {
  // DTDs/entities are unnecessary for this schema. Disable expansion and reject
  // declarations before parsing to prevent external entities and expansion DoS.
  if (xml.includes('<!DOCTYPE') || xml.includes('<!ENTITY')
    || XMLValidator.validate(xml, { allowBooleanAttributes: false }) !== true) return false;
  const document = parser.parse(xml);
  if (!onlyChildren(document, ['OperationStateResponse'])) return false;
  const response = document.OperationStateResponse;
  if (!record(response) || response['@_xmlns'] !== NAMESPACE
    || !validNamespaces(response, true)) return false;
  const { Result: result, State: state, Info: info, UserFields: userFields } = response;
  if (!record(result) || result.Code !== '0' || !record(state) || state.Code !== '100'
    || !record(info) || !record(userFields)) return false;
  if (![response, state, info].every(value => echoedContextMatches(value, order))) return false;
  // IncSum is the BUYER currency amount and must never be substituted for OutSum.
  // A non-ruble settlement or a net-of-fee amount is deliberately not inferred.
  // Such a response must wait for the signed ResultURL or operator reconciliation.
  if (!['RUB', 'RUR'].includes(info.OutCurrLabel)
    || kopecks(info.OutSum) !== kopecks(order.amount)) return false;
  if (!onlyChildren(userFields, ['Field'])) return false;
  const fields = userFields.Field;
  const expected = new Map([
    ['Shp_license', order.license_id],
    ['Shp_mode', 'live'],
    ['Shp_product', PRODUCT],
    ['Shp_store', order.store],
  ]);
  if (!Array.isArray(fields) || fields.length !== expected.size) return false;
  const seen = new Set();
  for (const field of fields) {
    if (!onlyChildren(field, ['Name', 'Value']) || typeof field.Name !== 'string'
      || typeof field.Value !== 'string' || seen.has(field.Name)
      || !expected.has(field.Name) || expected.get(field.Name) !== field.Value) return false;
    seen.add(field.Name);
  }
  return seen.size === expected.size;
}

async function readXml(response) {
  const contentType = (response.headers.get('Content-Type') || '').split(';', 1)[0].trim().toLowerCase();
  if (!['text/xml', 'application/xml'].includes(contentType)
    || Number(response.headers.get('Content-Length') || 0) > MAX_RESPONSE_BYTES
    || !response.body) return null;
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_RESPONSE_BYTES) {
        void reader.cancel().catch(() => {});
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

/**
 * Recover an immutable LIVE D1 order when ResultURL is delayed or lost.
 * Requires ROBOKASSA_MERCHANT_LOGIN and LIVE ROBOKASSA_PASSWORD2 (SHA-256).
 * Returns false for test orders, incomplete/mismatched evidence, or any failure.
 * Does not mutate the database, sign licences, inspect checkout availability, or
 * depend on the currently selected ROBOKASSA_MODE. The caller must authenticate
 * the order owner and rate-limit API requests before calling this function.
 *
 * OpStateExt has no response signature or documented merchant/invoice echo.
 * Verification relies on the exact signed request over HTTPS to the fixed host,
 * Result.Code=0, State.Code=100, amount/currency, and ALL four immutable Shp_ fields.
 */
export async function confirmLivePayment(order, env) {
  let timeout;
  const controller = new AbortController();
  try {
    if (!validOrder(order, env)) return false;
    const signatureBytes = await crypto.subtle.digest('SHA-256', encoder.encode(
      [order.merchant_login, String(order.invoice_id), env.ROBOKASSA_PASSWORD2].join(':')));
    const signature = Array.from(new Uint8Array(signatureBytes), n => n.toString(16).padStart(2, '0')).join('');
    const check = async () => {
      const response = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'text/xml, application/xml' },
        body: new URLSearchParams({
          MerchantLogin: order.merchant_login,
          InvoiceID: String(order.invoice_id),
          Signature: signature,
        }).toString(),
        redirect: 'error',
        credentials: 'omit',
        cache: 'no-store',
        signal: controller.signal,
      });
      if (response.status !== 200 || response.redirected
        || (response.url && response.url !== ENDPOINT)) return false;
      const xml = await readXml(response);
      return xml !== null && confirmsOrder(xml, order);
    };
    // Bound the entire exchange, including a response body that stalls after
    // headers. Abort alone is not sufficient if an underlying stream misbehaves.
    const deadline = new Promise(resolve => {
      timeout = setTimeout(() => { controller.abort(); resolve(false); }, TIMEOUT_MS);
    });
    return await Promise.race([check().catch(() => false), deadline]);
  } catch {
    return false;
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
    controller.abort();
  }
}
