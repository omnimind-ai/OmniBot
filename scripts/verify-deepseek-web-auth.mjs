// Verify the official token -> Cookie -> index exchange, with no secret output.
// Source must be the original login-link UI snapshot from the same live Web process.
// Usage: ADB=/path/to/adb node scripts/verify-deepseek-web-auth.mjs emulator-N SNAPSHOT FORWARD_PORT SERVICE_PORT
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {request as httpRequest} from 'node:http';
const [serial, snapshot, forwardPort, servicePort] = process.argv.slice(2);
assert(/^emulator-\d+$/.test(serial || '') &&
  /^\/data\/local\/tmp\/oob-[a-z0-9-]+\.xml$/.test(snapshot || '') &&
  /^\d+$/.test(forwardPort || '') && /^\d+$/.test(servicePort || ''));
const xml = execFileSync(process.env.ADB || 'adb', ['-s', serial, 'shell', 'cat', snapshot],
  {encoding: 'utf8', timeout: 15000});
const candidates = [...xml.matchAll(/(?:text|content-desc)="([^"]*)"/g)]
  .map(m => m[1].replaceAll('&amp;', '&'))
  .filter(value => new RegExp(`^(?:http://)?127\\.0\\.0\\.1:${servicePort}/\\?token=[A-Za-z0-9_-]+$`).test(value));
assert.equal(candidates.length, 1, 'Expected one original official login URL');
const original = new URL(candidates[0].startsWith('http://') ? candidates[0] : `http://${candidates[0]}`);
const forwarded = new URL(original);
forwarded.port = forwardPort;
const request = (url, headers) => new Promise((resolve, reject) => {
  // Preserve the browser authority across adb port forwarding. Fetch may
  // replace Host with the forwarding port, invalidating authority-bound cookies.
  const req = httpRequest(url, {headers}, response => {
    response.resume();
    resolve({status: response.statusCode, headers: {
      get: name => {
        const value = response.headers[name];
        return Array.isArray(value) ? value[0] : value ?? null;
      },
    }});
  });
  req.setTimeout(15000, () => req.destroy(new Error('Observation timed out')));
  req.on('error', reject);
  req.end();
});
try {
  const exchange = await request(forwarded, {Host: original.host});
  const cookie = exchange.headers.get('set-cookie');
  console.log(JSON.stringify({exchangeStatus: exchange.status, cookieIssued: !!cookie}));
  assert([200, 303].includes(exchange.status), 'Official login token was not exchanged');
  assert(cookie, 'Official exchange did not issue a Cookie');
  if (exchange.status === 303) {
    assert.equal(exchange.headers.get('location'), '/', 'Unexpected login redirect');
  } else {
    assert(exchange.headers.get('content-type')?.startsWith('text/html'), 'Expected same-origin login document');
  }
  const index = await request(`http://127.0.0.1:${forwardPort}/`, {
    Host: original.host, Cookie: cookie.split(';')[0],
  });
  console.log(JSON.stringify({authenticatedIndexStatus: index.status,
    contentType: index.headers.get('content-type')}));
  assert.equal(index.status, 200, 'Official Cookie did not authorize the index');
} catch (error) {
  if (error.code === 'ERR_ASSERTION') throw error;
  throw new Error('Local authentication observation failed without exposing request details');
}
