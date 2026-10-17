import { generateKeyPairSync } from 'node:crypto';
import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const folder = resolve(process.argv[2] || '.secrets');
const privatePath = resolve(folder, 'license-private-key.txt');
const publicPath = resolve(folder, 'license-public-key.txt');
if (existsSync(privatePath) || existsSync(publicPath)) {
  throw new Error('Key files already exist. Refusing to overwrite a licence signing identity.');
}
const { privateKey, publicKey } = generateKeyPairSync('rsa', {
  modulusLength: 3072,
  publicKeyEncoding: { type: 'spki', format: 'der' },
  privateKeyEncoding: { type: 'pkcs8', format: 'der' },
});
mkdirSync(folder, { recursive: true, mode: 0o700 });
writeFileSync(privatePath, privateKey.toString('base64'), { mode: 0o600, flag: 'wx' });
writeFileSync(publicPath, publicKey.toString('base64'), { mode: 0o600, flag: 'wx' });
console.log('Created licence signing key files. No key material was printed.');
console.log('Private key: ' + privatePath + ' (Worker secret LICENSE_PRIVATE_KEY only)');
console.log('Public key: ' + publicPath + ' (Android payment.publicKeyBase64)');
console.log('Back up the private file securely. Do not commit it or rotate it while installed apps use this public key.');
