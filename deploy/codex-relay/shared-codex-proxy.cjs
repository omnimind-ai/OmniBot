#!/usr/bin/env node
// Transport-only CODEX_PATH entry for upstream codex-acp. No session translation,
// retries, prompt replay or ownership of the shared backend process.
const fs = require('node:fs');
const readline = require('node:readline');
const WebSocket = require('ws');
if (process.argv[2] !== 'app-server') throw Error('Only app-server transport is supported');
const url = process.env.OOB_SHARED_CODEX_URL;
const socketPath = process.env.OOB_SHARED_CODEX_SOCKET;
const tokenFile = process.env.OOB_SHARED_CODEX_TOKEN_FILE;
if (!!url === !!socketPath) throw Error('Select exactly one explicit shared URL or Unix socket');
let endpoint = url;
let headers;
if (socketPath) {
  if (!require('node:path').isAbsolute(socketPath) || socketPath.includes(':')) throw Error('Absolute Unix socket path without colon required');
  const stat = fs.lstatSync(socketPath);
  if (!stat.isSocket() || stat.uid !== process.getuid() || (stat.mode & 0o077)) throw Error('Shared socket must be private and owned by this user');
  endpoint = `ws+unix://${socketPath}:/`;
} else {
  if (!tokenFile) throw Error('Token file required for shared URL');
  const token = fs.readFileSync(tokenFile, 'utf8').trim();
  if (!token) throw Error('Empty shared token');
  headers = { Authorization: `Bearer ${token}` };
}
process.stdin.pause();
const socket = new WebSocket(endpoint, {
  headers, handshakeTimeout: 10000,
  // Official daemon control sockets reject unsupported extension negotiation.
  perMessageDeflate: false,
  maxPayload: 32 * 1024 * 1024,
});
socket.on('error', () => { console.error('Shared Codex transport failed'); process.exitCode = 1; });
socket.on('open', () => {
  const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  lines.on('line', line => {
    if (!line.trim()) return;
    if (socket.readyState !== WebSocket.OPEN) return;
    if (socket.bufferedAmount > 32 * 1024 * 1024) {
      console.error('Shared Codex transport backpressure limit exceeded');
      socket.terminate(); process.exitCode = 1; return;
    }
    socket.send(line);
  });
  lines.on('close', () => socket.close());
  process.stdin.resume();
});
socket.on('message', bytes => {
  if (!process.stdout.write(bytes.toString() + '\n')) socket.pause();
});
process.stdout.on('drain', () => socket.resume());
socket.on('close', () => { process.stdin.destroy(); });
process.on('SIGTERM', () => { socket.terminate(); process.stdin.destroy(); });
process.on('SIGINT', () => { socket.terminate(); process.stdin.destroy(); });
