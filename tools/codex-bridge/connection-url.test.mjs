import test from 'node:test';
import assert from 'node:assert/strict';
import { publicBridgeUrl } from './connection-url.mjs';
import { spawn } from 'node:child_process';
import net from 'node:net';
import os from 'node:os';
import { once } from 'node:events';

test('external TLS address is independent of the local listen port', () => {
  assert.equal(publicBridgeUrl('wss://bridge.example.com'), 'wss://bridge.example.com/codex');
  assert.equal(publicBridgeUrl('wss://bridge.example.com:8443/codex'), 'wss://bridge.example.com:8443/codex');
  assert.equal(publicBridgeUrl('ws://[::1]:17321/codex'), 'ws://[::1]:17321/codex');
  assert.equal(publicBridgeUrl(undefined), '');
});

test('reject addresses incompatible with the existing readiness and file routes', () => {
  for (const value of ['https://bridge.example.com', 'wss://bridge.example.com/prefix/codex',
    'wss://user:secret@bridge.example.com/codex', 'wss://bridge.example.com/codex?token=secret',
    'wss://bridge.example.com/codex#secret', 'not a URL']) {
    assert.throws(() => publicBridgeUrl(value));
  }
});

for (const showPairing of [false, true]) {
test(showPairing ? 'explicit Bridge QR carries the public endpoint, credential and workspace' :
  'background Bridge logs never expose pairing credentials', {timeout: 15000}, async () => {
  const reservation = net.createServer();
  reservation.listen(0, '127.0.0.1');
  await once(reservation, 'listening');
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const child = spawn(process.execPath, [new URL('./server.mjs', import.meta.url).pathname,
    '--host', '127.0.0.1', '--port', String(port), '--cwd', os.tmpdir(),
    '--public-url', 'wss://bridge.example.com/codex', '--token', 'synthetic-pairing-test',
    '--acp-bin', process.execPath, '--no-interactive', ...(showPairing ? ['--show-pairing'] : [])],
    {env: {...process.env, NO_COLOR: '1'}});
  let output = '';
  const exited = once(child, 'exit');
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(Error('No Bridge pairing payload')), 10000);
      child.stdout.on('data', data => {
        output += data.toString();
        if (output.includes(showPairing ? 'Quick connect payload' : 'Pairing credentials hidden.')) {
          clearTimeout(timer); resolve();
        }
      });
      child.stderr.on('data', data => { output += data.toString(); });
      child.once('error', error => { clearTimeout(timer); reject(error); });
      child.once('exit', () => { clearTimeout(timer); reject(Error('Bridge exited before pairing')); });
    });
    if (!showPairing) {
      assert.ok(output.includes('Token auth'));
      assert.ok(!output.includes('synthetic-pairing-test'));
      assert.ok(!output.includes('omnibot://'));
      assert.ok(!output.includes('Bridge token'));
      return;
    }
    const payload = new URL(output.match(/omnibot:\/\/codex-bridge\?[^\s\u001b]+/)[0]);
    assert.equal(payload.searchParams.get('bridgeUrl'), 'wss://bridge.example.com/codex');
    assert.equal(payload.searchParams.get('token'), 'synthetic-pairing-test');
    assert.equal(payload.searchParams.get('cwd'), os.tmpdir());
  } finally {
    child.kill('SIGTERM');
    await exited;
  }
});
}
