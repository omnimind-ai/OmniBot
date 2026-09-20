import {readFile, mkdir, mkdtemp, chmod, writeFile} from 'node:fs/promises';
import path from 'node:path';
import QRCode from 'qrcode';
import {publicBridgeUrl} from './connection-url.mjs';
import {inspectSessionOwner} from './shared-session-owner.mjs';

export async function prepareSessionConnection(config, sessionId) {
  if (typeof sessionId !== 'string' || !sessionId.trim()) throw Error('sessionId is required');
  const bridgeUrl = publicBridgeUrl(config.publicUrl);
  if (!bridgeUrl || !config.backendUrl || !path.isAbsolute(config.tokenFile || '') ||
      !path.isAbsolute(config.outputDir || '')) {
    throw Error('Configure publicUrl, backendUrl, and absolute tokenFile/outputDir paths');
  }
  const backendToken = config.backendTokenFile
    ? (await readFile(config.backendTokenFile, 'utf8')).trim() : undefined;
  const owner = await inspectSessionOwner({url: config.backendUrl, sessionId, token: backendToken});
  if (!owner.loadedOnBackend) {
    return {ok: false, code: 'SESSION_NOT_LOADED_ON_BACKEND',
      message: 'This session is not running on the configured Bridge backend. No session was resumed, copied or started.'};
  }
  const token = (await readFile(config.tokenFile, 'utf8')).trim();
  if (!token) throw Error('Bridge token file is empty');
  const payload = JSON.stringify({type: 'omnibot.codex_bridge', bridgeUrl,
    token, cwd: owner.cwd, sessionId});
  await mkdir(config.outputDir, {recursive: true, mode: 0o700});
  const directory = await mkdtemp(path.join(config.outputDir, 'session-'));
  await chmod(directory, 0o700);
  const qrPath = path.join(directory, 'connect.png');
  const image = await QRCode.toBuffer(payload, {type: 'png', width: 640, margin: 4});
  await writeFile(qrPath, image, {mode: 0o600});
  const sshHost = typeof config.desktopSshHost === 'string'
    ? config.desktopSshHost.trim() : '';
  return {ok: true, sessionId, qrPath,
    desktop: sshHost ? {sshHost, cwd: owner.cwd, sessionId,
      message: 'In desktop Codex, select this SSH connection and open the session from that host. A local thread link does not select this SSH backend. Backend ownership was checked; desktop UI routing has not been verified.'}
      : {message: 'Desktop SSH connection is not configured in this connection helper. Do not open a local thread link as a substitute for the shared backend.'},
    message: 'Scan this private QR in Xiaowan to open the selected session. It contains your Bridge credential; it is not a public share link.'};
}
