import { describe, it, expect } from 'vitest';
import {
  encryptText,
  decryptText,
  sha256Hex,
  generateSecureToken,
} from '../src/crypto/encryption';

describe('Crypto & Encryption', () => {
  const masterKey = '0b60449fa3da9fc88b4cd40c0eb4cb3e480bd2a1d212f27acf20154dac31d542';
  const otherKey = '1111111111111111111111111111111111111111111111111111111111111111';

  it('encrypts and decrypts correctly', async () => {
    const original = 'MySecretPassword123!@#';
    const encrypted = await encryptText(original, masterKey);

    expect(encrypted.ciphertext).toBeDefined();
    expect(encrypted.iv).toBeDefined();
    expect(encrypted.ciphertext).not.toBe(original);

    const decrypted = await decryptText(encrypted.ciphertext, encrypted.iv, masterKey);
    expect(decrypted).toBe(original);
  });

  it('uses random IV for each encryption', async () => {
    const text = 'RepeatTest';
    const enc1 = await encryptText(text, masterKey);
    const enc2 = await encryptText(text, masterKey);

    expect(enc1.iv).not.toBe(enc2.iv);
    expect(enc1.ciphertext).not.toBe(enc2.ciphertext);

    expect(await decryptText(enc1.ciphertext, enc1.iv, masterKey)).toBe(text);
    expect(await decryptText(enc2.ciphertext, enc2.iv, masterKey)).toBe(text);
  });

  it('fails decryption with wrong key', async () => {
    const encrypted = await encryptText('SecretMessage', masterKey);
    await expect(decryptText(encrypted.ciphertext, encrypted.iv, otherKey)).rejects.toThrow();
  });

  it('rejects malformed master keys', async () => {
    await expect(encryptText('payload', 'z'.repeat(64))).rejects.toThrow('Invalid hex key encoding');
  });

  it('computes sha256Hex correctly', async () => {
    // SHA256 of "hello" is 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
    const hash = await sha256Hex('hello');
    expect(hash).toBe('2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824');
  });

  it('generates secure 256-bit token', () => {
    const token = generateSecureToken();
    expect(token).toHaveLength(64); // 32 bytes = 64 hex chars
    const token2 = generateSecureToken();
    expect(token).not.toBe(token2);
  });
});
