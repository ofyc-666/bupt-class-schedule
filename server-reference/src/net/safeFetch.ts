export const ALLOWED_HOSTS = new Set([
  'auth.bupt.edu.cn',
  'ucloud.bupt.edu.cn',
  'apiucloud.bupt.edu.cn',
  'www.pushplus.plus',
]);

export class SecurityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SecurityError';
  }
}

export function assertAllowedUrl(urlString: string): URL {
  let url: URL;
  try {
    url = new URL(urlString);
  } catch {
    throw new SecurityError('Invalid URL format');
  }

  if (url.protocol !== 'https:') {
    throw new SecurityError(`Forbidden protocol: ${url.protocol}`);
  }

  const host = url.hostname.toLowerCase();
  if (!ALLOWED_HOSTS.has(host)) {
    throw new SecurityError(`Forbidden host: ${host}`);
  }

  if (url.port && url.port !== '443') {
    throw new SecurityError(`Forbidden port: ${url.port}`);
  }
  if (url.username || url.password) {
    throw new SecurityError('URL credentials are not allowed');
  }

  return url;
}

export interface SafeFetchOptions {
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  timeoutMs?: number;
  redirect?: 'error' | 'manual';
}

export async function safeFetch(urlString: string, options: SafeFetchOptions = {}): Promise<Response> {
  const url = assertAllowedUrl(urlString);
  const timeoutMs = options.timeoutMs ?? 15000;
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(url.toString(), {
      method: options.method || 'GET',
      headers: {
        'User-Agent': 'BUPT-Class-Schedule-Server/1.0',
        ...(options.headers || {}),
      },
      body: options.body,
      redirect: options.redirect || 'manual', // never follow redirects outside the host allowlist
      signal: controller.signal,
    });
    return response;
  } finally {
    clearTimeout(timeoutId);
  }
}
