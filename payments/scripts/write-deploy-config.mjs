import { readFileSync, writeFileSync } from 'node:fs';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const config = JSON.parse(readFileSync(new URL('../wrangler.jsonc', import.meta.url), 'utf8'));
const values = process.env;
if (!uuid.test(values.PAYMENT_D1_DATABASE_ID || '')) throw new Error('Set PAYMENT_D1_DATABASE_ID to the created D1 database UUID.');
if (!/^[A-Za-z0-9_.-]{1,100}$/.test(values.ROBOKASSA_MERCHANT_LOGIN || '')) throw new Error('Set ROBOKASSA_MERCHANT_LOGIN.');
const url = new URL(values.PAYMENT_API_BASE_URL || '');
if (url.protocol !== 'https:' || url.pathname !== '/' || url.username || url.password || url.search || url.hash) {
  throw new Error('PAYMENT_API_BASE_URL must be an HTTPS origin without a path, credentials or query.');
}
if (!['test', 'live'].includes(values.PAYMENT_MODE)) throw new Error('PAYMENT_MODE must be test or live.');
if (!['true', 'false'].includes(values.ENABLE_PAYMENTS)) throw new Error('ENABLE_PAYMENTS must be true or false.');
config.d1_databases[0].database_id = values.PAYMENT_D1_DATABASE_ID;
config.vars.ROBOKASSA_MERCHANT_LOGIN = values.ROBOKASSA_MERCHANT_LOGIN;
config.vars.PUBLIC_BASE_URL = url.origin;
config.vars.ROBOKASSA_MODE = values.PAYMENT_MODE;
config.vars.PAYMENTS_ENABLED = process.argv.includes('--disabled') ? 'false' : values.ENABLE_PAYMENTS;
writeFileSync(new URL('../wrangler.local.json', import.meta.url), JSON.stringify(config, null, 2) + '\n');
console.log('Deployment configuration prepared. No credentials written to it.');
