#!/usr/bin/env node

import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import readlineTerminal from 'node:readline';
import readline from 'node:readline/promises';
import { spawn, execFile } from 'node:child_process';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { WebSocketServer } from 'ws';

const require = createRequire(import.meta.url);
const qrcode = require('qrcode-terminal');
const bridgePackage = require('./package.json');
const isWindows = process.platform === 'win32';
const bridgeStartedAt = Date.now();
let activeConnections = 0;

function printHelp() {
  console.log(`Omnibot Codex Bridge

Usage:
  codex-bridge [project-dir] [options]
  omnibot-codex-bridge [project-dir] [options]
  npx @thuocean/codex-bridge --cwd /path/to/project --token auto

Options:
  --cwd <path>            Codex working directory. Defaults to current directory.
  --token <value|auto>    Bearer token. Use "auto" to generate one for this run.
  --no-token              Disable token auth.
  --host <host>           Listen host. Defaults to 0.0.0.0.
  --port <port>           Listen port. Defaults to 17321.
  --public-host <host>    Host/IP printed in the QR code.
  --acp-bin <path>        ACP agent executable. Defaults to codex-acp.
  --codex-home <path>     Optional CODEX_HOME override.
  --config <path>         Bridge config path for remembered manual token.
  --forget-token          Clear the remembered manual token before setup.
  --interactive           Force terminal setup prompts.
  --no-interactive        Start immediately without terminal prompts.
  -h, --help              Show this help.

Environment variables with the same meaning are also supported:
  OMNIBOT_BRIDGE_CWD, OMNIBOT_BRIDGE_TOKEN, OMNIBOT_BRIDGE_HOST,
  OMNIBOT_BRIDGE_PORT, OMNIBOT_BRIDGE_PUBLIC_HOST, CODEX_ACP_BIN, CODEX_HOME,
  OMNIBOT_BRIDGE_INTERACTIVE, OMNIBOT_BRIDGE_CONFIG`);
}

function readOptionValue(args, index, option) {
  const arg = args[index];
  const equalsIndex = arg.indexOf('=');
  if (equalsIndex >= 0) {
    return { value: arg.slice(equalsIndex + 1), nextIndex: index };
  }
  const value = args[index + 1];
  if (value == null || value.startsWith('-')) {
    throw new Error(`${option} requires a value.`);
  }
  return { value, nextIndex: index + 1 };
}

function parseCliArgs(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === '-h' || arg === '--help') {
      options.help = true;
      continue;
    }
    if (arg === '--no-token') {
      options.token = '';
      continue;
    }
    if (arg === '--interactive') {
      options.interactive = true;
      continue;
    }
    if (arg === '--no-interactive') {
      options.interactive = false;
      continue;
    }
    if (arg === '--forget-token') {
      options.forgetToken = true;
      continue;
    }
    const optionMap = {
      '--cwd': 'cwd',
      '--token': 'token',
      '--host': 'host',
      '--port': 'port',
      '--public-host': 'publicHost',
      '--acp-bin': 'codexBin',
      '--codex-home': 'codexHome',
      '--config': 'configPath',
    };
    const matchedOption = Object.keys(optionMap).find(
      (option) => arg === option || arg.startsWith(`${option}=`)
    );
    if (matchedOption) {
      const parsed = readOptionValue(args, index, matchedOption);
      options[optionMap[matchedOption]] = parsed.value;
      index = parsed.nextIndex;
      continue;
    }
    if (arg.startsWith('-')) {
      throw new Error(`Unknown option: ${arg}`);
    }
    if (!options.cwd) {
      options.cwd = arg;
      continue;
    }
    throw new Error(`Unexpected argument: ${arg}`);
  }
  return options;
}

function resolveToken(cliToken, rememberedToken = '') {
  const envToken = envOption('OMNIBOT_BRIDGE_TOKEN');
  const rawToken =
    cliToken === undefined
      ? envToken === undefined
        ? rememberedToken || ''
        : envToken
      : cliToken;
  return rawToken === 'auto' ? crypto.randomBytes(16).toString('hex') : rawToken;
}

function expandHomePath(rawPath) {
  const value = String(rawPath || '').trim();
  if (value === '~') return os.homedir();
  if (value.startsWith('~/') || value.startsWith('~\\')) {
    return path.join(os.homedir(), value.slice(2));
  }
  return value;
}

function envOption(name) {
  return Object.prototype.hasOwnProperty.call(process.env, name)
    ? process.env[name]
    : undefined;
}

function hasText(value) {
  return String(value ?? '').trim().length > 0;
}

function hasExplicitNetworkConfig(options) {
  return (
    hasText(options.host) ||
    hasText(options.publicHost) ||
    hasText(envOption('OMNIBOT_BRIDGE_HOST')) ||
    hasText(envOption('OMNIBOT_BRIDGE_PUBLIC_HOST'))
  );
}

function hasCommandTokenConfig(options) {
  return (
    Object.prototype.hasOwnProperty.call(options, 'token') ||
    envOption('OMNIBOT_BRIDGE_TOKEN') !== undefined
  );
}

function hasExplicitTokenConfig(options) {
  return (
    hasCommandTokenConfig(options) ||
    hasText(options.rememberedToken)
  );
}

function envInteractiveEnabled() {
  const raw = envOption('OMNIBOT_BRIDGE_INTERACTIVE');
  if (raw === undefined) return null;
  const normalized = String(raw).trim().toLowerCase();
  if (['0', 'false', 'no', 'off'].includes(normalized)) return false;
  if (['1', 'true', 'yes', 'on'].includes(normalized)) return true;
  return null;
}

function shouldRunInteractiveSetup(options) {
  if (!process.stdin.isTTY || !process.stdout.isTTY) return false;
  if (options.interactive === false) return false;
  if (options.interactive === true) return true;
  const envInteractive = envInteractiveEnabled();
  if (envInteractive === false) return false;
  if (envInteractive === true) return true;
  return !hasExplicitNetworkConfig(options) || !hasExplicitTokenConfig(options);
}

function setupCancelledError() {
  const error = new Error('Setup cancelled.');
  error.code = 'OMNIBOT_BRIDGE_SETUP_CANCELLED';
  return error;
}

const PROMPT_BACK = Symbol('prompt-back');

function shouldUseColor() {
  return process.stdout.isTTY && !Object.prototype.hasOwnProperty.call(process.env, 'NO_COLOR');
}

function paint(code, value) {
  const text = String(value);
  return shouldUseColor() ? `\x1b[${code}m${text}\x1b[0m` : text;
}

const color = {
  accent: (text) => paint('35', text),
  dim: (text) => paint('2', text),
  error: (text) => paint('31', text),
  green: (text) => paint('32', text),
  gray: (text) => paint('90', text),
  heading: (text) => paint('1;36', text),
  input: (text) => paint('1;32', text),
  key: (text) => paint('33', text),
  label: (text) => paint('90', text),
  prompt: (text) => paint('36', text),
  selected: (text) => paint('1;96', text),
  value: (text) => paint('32', text),
  warn: (text) => paint('33', text),
  white: (text) => paint('97', text),
};

function terminalContentWidth() {
  return Math.max(20, (process.stdout.columns || 100) - 1);
}

function visibleLength(value) {
  const text = String(value);
  let length = 0;
  for (let index = 0; index < text.length;) {
    if (text[index] === '\x1b' && text[index + 1] === '[') {
      const end = text.indexOf('m', index + 2);
      if (end >= 0) {
        index = end + 1;
        continue;
      }
    }
    const codePoint = text.codePointAt(index);
    index += codePoint > 0xffff ? 2 : 1;
    length += 1;
  }
  return length;
}

function truncateAnsiLine(value, maxWidth = terminalContentWidth()) {
  const text = String(value);
  if (visibleLength(text) <= maxWidth) return text;
  const suffix = maxWidth >= 4 ? '...' : '.';
  const limit = Math.max(0, maxWidth - suffix.length);
  let length = 0;
  let output = '';
  for (let index = 0; index < text.length;) {
    if (text[index] === '\x1b' && text[index + 1] === '[') {
      const end = text.indexOf('m', index + 2);
      if (end >= 0) {
        output += text.slice(index, end + 1);
        index = end + 1;
        continue;
      }
    }
    const codePoint = text.codePointAt(index);
    if (length >= limit) break;
    output += String.fromCodePoint(codePoint);
    index += codePoint > 0xffff ? 2 : 1;
    length += 1;
  }
  const reset = shouldUseColor() ? '\x1b[0m' : '';
  return `${output}${reset}${color.dim(suffix)}`;
}

function renderChoiceLine(choice, index, selectedIndex, defaultIndex, inputState = null) {
  const selected = index === selectedIndex;
  const pointer = selected ? color.green('>') : color.gray(' ');
  if (inputState && selected) {
    const inlineValue = inputState.value;
    const placeholder = inputState.defaultValue || inputState.placeholder || '';
    const renderedValue = inlineValue
      ? color.input(inlineValue)
      : color.dim(placeholder);
    return truncateAnsiLine(`${pointer} ${renderedValue}`);
  }
  const marker = index === defaultIndex ? color.key(' (default)') : '';
  const detail = choice.detail ? color.gray(` - ${choice.detail}`) : '';
  const label = selected ? color.selected(choice.label) : color.white(choice.label);
  return truncateAnsiLine(`${pointer} ${label}${marker}${detail}`);
}

function renderChoices(choices, selectedIndex, defaultIndex, inputState = null) {
  choices.forEach((choice, index) => {
    process.stdout.write(
      `${renderChoiceLine(choice, index, selectedIndex, defaultIndex, inputState)}\n`
    );
  });
}

function logField(label, value, valueStyle = color.value) {
  console.log(`${color.label(`${label}:`)} ${valueStyle(value)}`);
}

function defaultBridgeConfigPath() {
  return path.join(os.homedir(), '.omnibot', 'codex-bridge.json');
}

function resolveBridgeConfigPath(options) {
  return expandHomePath(
    options.configPath ||
      process.env.OMNIBOT_BRIDGE_CONFIG ||
      defaultBridgeConfigPath()
  );
}

async function readBridgeConfig(configPath) {
  try {
    const raw = await fs.readFile(configPath, 'utf8');
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (error) {
    if (error?.code === 'ENOENT') return {};
    console.log(color.warn(`Could not read bridge config at ${configPath}: ${error.message}`));
    return {};
  }
}

async function writeBridgeConfig(configPath, config) {
  const nextConfig = {};
  if (hasText(config.token)) {
    nextConfig.token = String(config.token);
  }
  await fs.mkdir(path.dirname(configPath), { recursive: true });
  await fs.writeFile(configPath, `${JSON.stringify(nextConfig, null, 2)}\n`, {
    mode: 0o600,
  });
  try {
    await fs.chmod(configPath, 0o600);
  } catch {
    // Best effort on platforms/filesystems that do not support POSIX modes.
  }
}

async function promptChoice(title, choices, defaultIndex = 0, options = {}) {
  if (choices.length === 0) {
    throw new Error(`${title} has no choices.`);
  }
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    return choices[defaultIndex];
  }
  let selectedIndex = Math.min(Math.max(defaultIndex, 0), choices.length - 1);
  const renderedLines = choices.length;
  let inputState = null;

  process.stdout.write(`\n${color.heading(title)}\n`);
  const hasInlineInput = choices.some((choice) => choice.input);
  const escHelp = options.allowBack
    ? ` ${color.key('Esc')} goes back.`
    : hasInlineInput
      ? ` ${color.key('Esc')} leaves input.`
      : '';
  process.stdout.write(
    `${color.dim('Use')} ${color.key('Up/Down')} ${color.dim('and')} ` +
      `${color.key('Enter')}.${escHelp} ${color.key('Ctrl-C')} ${color.dim('cancels.')}\n`
  );
  renderChoices(choices, selectedIndex, defaultIndex);

  return new Promise((resolve, reject) => {
    const previousRawMode = process.stdin.isRaw;
    readlineTerminal.emitKeypressEvents(process.stdin);

    function cleanup() {
      process.stdin.off('keypress', onKeypress);
      if (process.stdin.setRawMode) {
        process.stdin.setRawMode(Boolean(previousRawMode));
      }
      process.stdin.pause();
    }

    function rerender() {
      readlineTerminal.moveCursor(process.stdout, 0, -renderedLines);
      readlineTerminal.clearScreenDown(process.stdout);
      renderChoices(choices, selectedIndex, defaultIndex, inputState);
    }

    function finish() {
      const selectedChoice = choices[selectedIndex];
      if (selectedChoice.input) {
        inputState = {
          defaultValue: selectedChoice.input.defaultValue || '',
          placeholder: selectedChoice.input.placeholder || '',
          value: '',
        };
        rerender();
        return;
      }
      cleanup();
      process.stdout.write('\n');
      resolve(selectedChoice);
    }

    function finishInput() {
      const selectedChoice = choices[selectedIndex];
      const rawValue = inputState.value || inputState.defaultValue || '';
      const inputValue = selectedChoice.input?.trim === false ? rawValue : rawValue.trim();
      cleanup();
      process.stdout.write('\n');
      resolve({ ...selectedChoice, inputValue });
    }

    function cancel() {
      cleanup();
      process.stdout.write('\n');
      reject(setupCancelledError());
    }

    function back() {
      cleanup();
      process.stdout.write('\n');
      resolve(PROMPT_BACK);
    }

    function move(delta) {
      if (inputState) return;
      selectedIndex = (selectedIndex + delta + choices.length) % choices.length;
      rerender();
    }

    function onKeypress(input, key) {
      if ((key?.ctrl && key.name === 'c') || input === '\u0003') {
        cancel();
        return;
      }
      if (key?.name === 'return' || key?.name === 'enter' || input === '\r' || input === '\n') {
        if (inputState) {
          finishInput();
        } else {
          finish();
        }
        return;
      }
      if (key?.name === 'escape' || input === '\u001b') {
        if (inputState) {
          inputState = null;
          rerender();
        } else if (options.allowBack) {
          back();
        } else {
          process.stdout.write('\x07');
        }
        return;
      }
      if (inputState) {
        if (key?.name === 'backspace' || key?.name === 'delete') {
          inputState.value = inputState.value.slice(0, -1);
          rerender();
          return;
        }
        if (!input || key?.ctrl || key?.meta) {
          return;
        }
        inputState.value += input;
        rerender();
        return;
      }
      if (
        key?.name === 'up' ||
        key?.name === 'k' ||
        (key?.ctrl && key.name === 'p')
      ) {
        move(-1);
        return;
      }
      if (
        key?.name === 'down' ||
        key?.name === 'j' ||
        (key?.ctrl && key.name === 'n')
      ) {
        move(1);
      }
    }

    if (process.stdin.setRawMode) {
      process.stdin.setRawMode(true);
    }
    process.stdin.resume();
    process.stdin.on('keypress', onKeypress);
  });
}

async function promptTextFallback(question, defaultValue) {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });
  const suffix = defaultValue ? ` [${defaultValue}]` : '';
  try {
    const answer = (await rl.question(`${question}${suffix}: `)).trim();
    return answer || defaultValue;
  } finally {
    rl.close();
  }
}

async function promptText(question, defaultValue = '', options = {}) {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    return promptTextFallback(question, defaultValue);
  }
  const suffix = defaultValue ? color.gray(` [${defaultValue}]`) : '';
  const prompt = `${color.green('>')} ${color.prompt(question)}${suffix}: `;
  let value = '';

  process.stdout.write(prompt);

  return new Promise((resolve, reject) => {
    const previousRawMode = process.stdin.isRaw;
    readlineTerminal.emitKeypressEvents(process.stdin);

    function cleanup() {
      process.stdin.off('keypress', onKeypress);
      if (process.stdin.setRawMode) {
        process.stdin.setRawMode(Boolean(previousRawMode));
      }
      process.stdin.pause();
    }

    function rerender() {
      readlineTerminal.clearLine(process.stdout, 0);
      readlineTerminal.cursorTo(process.stdout, 0);
      process.stdout.write(`${prompt}${value}`);
    }

    function finish() {
      cleanup();
      process.stdout.write('\n');
      resolve(value.trim() || defaultValue);
    }

    function cancel() {
      cleanup();
      process.stdout.write('\n');
      reject(setupCancelledError());
    }

    function back() {
      cleanup();
      process.stdout.write('\n');
      resolve(PROMPT_BACK);
    }

    function onKeypress(input, key) {
      if ((key?.ctrl && key.name === 'c') || input === '\u0003') {
        cancel();
        return;
      }
      if (key?.name === 'return' || key?.name === 'enter' || input === '\r' || input === '\n') {
        finish();
        return;
      }
      if (key?.name === 'escape' || input === '\u001b') {
        if (options.allowBack) {
          back();
        } else {
          process.stdout.write('\x07');
        }
        return;
      }
      if (key?.name === 'backspace' || key?.name === 'delete') {
        value = value.slice(0, -1);
        rerender();
        return;
      }
      if (!input || key?.ctrl || key?.meta) {
        return;
      }
      value += input;
      rerender();
    }

    if (process.stdin.setRawMode) {
      process.stdin.setRawMode(true);
    }
    process.stdin.resume();
    process.stdin.on('keypress', onKeypress);
  });
}

async function promptNetworkConfig(options) {
  if (!shouldPromptNetworkConfig(options)) {
    return {};
  }
  const addresses = listAdvertisableIpv4Addresses();
  const choices = addresses.map((entry) => ({
    label: `${entry.address} (${entry.name})`,
    detail: 'listen on this LAN address and advertise it',
    host: entry.address,
    publicHost: entry.address,
  }));
  if (addresses.length > 0) {
    choices.push({
      label: '0.0.0.0 (all interfaces)',
      detail: `listen everywhere, advertise ${addresses[0].address}`,
      host: '0.0.0.0',
      publicHost: addresses[0].address,
    });
  } else {
    choices.push({
      label: '0.0.0.0 (all interfaces)',
      detail: 'listen everywhere',
      host: '0.0.0.0',
      publicHost: '',
    });
  }
  choices.push({
    label: '127.0.0.1 (localhost only)',
    detail: 'for adb reverse, SSH tunnel, or local testing',
    host: '127.0.0.1',
    publicHost: '127.0.0.1',
  });
  choices.push({
    label: 'Custom host/IP',
    detail: 'type a listen address manually',
    custom: true,
    input: {
      defaultValue: '0.0.0.0',
      placeholder: '0.0.0.0',
    },
  });
  for (;;) {
    const selected = await promptChoice(
      'Select the address Codex Bridge should listen on',
      choices,
      0
    );
    if (!selected.custom) {
      return { host: selected.host, publicHost: selected.publicHost };
    }
    const host = selected.inputValue || '0.0.0.0';
    const publicHost = await promptText(
      'Phone-reachable host/IP shown in QR code',
      isWildcardHost(host) ? addresses[0]?.address || 'localhost' : host,
      { allowBack: true }
    );
    if (publicHost === PROMPT_BACK) {
      continue;
    }
    return { host, publicHost };
  }
}

function shouldPromptNetworkConfig(options) {
  if (options.interactive === true) return true;
  return !hasExplicitNetworkConfig(options);
}

async function promptTokenConfig(options, allowBack = false) {
  if (options.interactive !== true && hasCommandTokenConfig(options)) {
    return {};
  }
  let rememberedToken = String(options.rememberedToken || '');
  let clearRememberedToken = false;
  for (;;) {
    const tokenChoices = [];
    if (hasText(rememberedToken)) {
      tokenChoices.push(
        {
          label: 'Use remembered token',
          detail: 'skip setup and reuse the saved manual token',
          token: rememberedToken,
        },
        {
          label: 'Enter a new token',
          detail: 'replace the remembered manual token',
          manual: true,
          input: {
            placeholder: 'type new token',
          },
        },
        {
          label: 'Forget remembered token',
          detail: 'clear it and choose another token mode',
          forgetRememberedToken: true,
        }
      );
    } else {
      tokenChoices.push(
        {
          label: 'Auto-generate token',
          detail: 'only for this run',
          token: 'auto',
        },
        {
          label: 'Enter token manually',
          detail: 'save and reuse this token next time',
          manual: true,
          input: {
            placeholder: 'type token',
          },
        }
      );
    }
    if (hasText(rememberedToken)) {
      tokenChoices.push({
        label: 'Auto-generate token',
        detail: 'only for this run',
        token: 'auto',
      });
    }
    tokenChoices.push({
      label: 'No token',
      detail: 'only use on trusted networks',
      token: '',
    });
    const selected = await promptChoice(
      'Select token authentication',
      tokenChoices,
      0,
      { allowBack }
    );
    if (selected === PROMPT_BACK) {
      return PROMPT_BACK;
    }
    if (selected.forgetRememberedToken) {
      rememberedToken = '';
      clearRememberedToken = true;
      continue;
    }
    if (!selected.manual) {
      return { token: selected.token, clearRememberedToken };
    }
    const token = selected.inputValue || '';
    if (token) {
      return { token, rememberToken: true, clearRememberedToken: false };
    }
    console.log(color.warn('Token cannot be empty. Choose "No token" if you want to disable auth.'));
  }
}

async function interactiveSetup(options) {
  if (!shouldRunInteractiveSetup(options)) {
    return {};
  }
  console.log(`\n${color.heading('Omnibot Codex Bridge setup')}`);
  console.log(
    `${color.dim('Press')} ${color.key('Enter')} ${color.dim('to accept the highlighted choice.')}\n`
  );
  const shouldPromptNetwork = shouldPromptNetworkConfig(options);
  for (;;) {
    const networkConfig = await promptNetworkConfig(options);
    const tokenConfig = await promptTokenConfig(options, shouldPromptNetwork);
    if (tokenConfig === PROMPT_BACK) {
      continue;
    }
    return { ...networkConfig, ...tokenConfig };
  }
}

let cliOptions;
try {
  cliOptions = parseCliArgs(process.argv.slice(2));
} catch (error) {
  console.error(color.error(error.message));
  console.error(color.dim('Run with --help for usage.'));
  process.exit(1);
}

if (cliOptions.help) {
  printHelp();
  process.exit(0);
}

const bridgeConfigPath = resolveBridgeConfigPath(cliOptions);
let bridgeConfig = await readBridgeConfig(bridgeConfigPath);
if (cliOptions.forgetToken) {
  bridgeConfig = { ...bridgeConfig, token: '' };
  await writeBridgeConfig(bridgeConfigPath, bridgeConfig);
  console.log(color.warn(`Remembered bridge token cleared: ${bridgeConfigPath}`));
}
if (hasText(bridgeConfig.token)) {
  cliOptions.rememberedToken = String(bridgeConfig.token);
}

let interactiveOptions;
try {
  interactiveOptions = await interactiveSetup(cliOptions);
} catch (error) {
  if (error?.code === 'OMNIBOT_BRIDGE_SETUP_CANCELLED') {
    console.error(color.warn('Setup cancelled.'));
    process.exit(130);
  }
  throw error;
}
if (interactiveOptions.clearRememberedToken) {
  bridgeConfig = { ...bridgeConfig, token: '' };
  await writeBridgeConfig(bridgeConfigPath, bridgeConfig);
  cliOptions.rememberedToken = '';
  console.log(color.warn(`Remembered bridge token cleared: ${bridgeConfigPath}`));
}
if (interactiveOptions.rememberToken && hasText(interactiveOptions.token)) {
  bridgeConfig = { ...bridgeConfig, token: interactiveOptions.token };
  await writeBridgeConfig(bridgeConfigPath, bridgeConfig);
  cliOptions.rememberedToken = interactiveOptions.token;
  console.log(color.green(`Manual bridge token saved: ${bridgeConfigPath}`));
}
const port = Number.parseInt(
  cliOptions.port || process.env.OMNIBOT_BRIDGE_PORT || '17321',
  10
);
if (!Number.isFinite(port) || port <= 0 || port > 65535) {
  console.error(color.error(`Invalid bridge port: ${cliOptions.port || process.env.OMNIBOT_BRIDGE_PORT}`));
  process.exit(1);
}
const host =
  interactiveOptions.host ||
  cliOptions.host ||
  process.env.OMNIBOT_BRIDGE_HOST ||
  '0.0.0.0';
const publicHost =
  interactiveOptions.publicHost ||
  cliOptions.publicHost ||
  process.env.OMNIBOT_BRIDGE_PUBLIC_HOST ||
  '';
const token = resolveToken(
  Object.prototype.hasOwnProperty.call(interactiveOptions, 'token')
    ? interactiveOptions.token
    : cliOptions.token,
  cliOptions.rememberedToken
);
const codexBin = cliOptions.codexBin || process.env.CODEX_ACP_BIN || 'codex-acp';
const bridgeCwd = path.resolve(
  expandHomePath(
    cliOptions.cwd || process.env.OMNIBOT_BRIDGE_CWD || process.cwd()
  )
);
const codexHome = expandHomePath(
  cliOptions.codexHome || process.env.CODEX_HOME || ''
);
const homeDir = os.homedir();
const maxReadBytes = Number.parseInt(
  process.env.OMNIBOT_BRIDGE_MAX_READ_BYTES || `${12 * 1024 * 1024}`,
  10
);
const maxUploadBytes = Number.parseInt(
  process.env.OMNIBOT_BRIDGE_MAX_UPLOAD_BYTES || `${24 * 1024 * 1024}`,
  10
);
const jsonContentType = 'application/json; charset=utf-8';

function bridgePath() {
  const candidates = [
    process.env.PATH || '',
    process.env.Path || '',
    process.env.path || '',
    process.env.npm_config_prefix || '',
    process.env.NPM_CONFIG_PREFIX || '',
    process.env.PNPM_HOME || '',
    process.env.APPDATA ? path.join(process.env.APPDATA, 'npm') : '',
    process.env.USERPROFILE ? path.join(process.env.USERPROFILE, 'AppData/Roaming/npm') : '',
    process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Programs/nodejs') : '',
    process.env.ProgramFiles ? path.join(process.env.ProgramFiles, 'nodejs') : '',
    process.execPath ? path.dirname(process.execPath) : '',
    '/opt/homebrew/bin',
    '/usr/local/bin',
    '/usr/bin',
    '/bin',
    path.join(homeDir, '.npm-global/bin'),
    path.join(homeDir, '.local/bin'),
    path.join(homeDir, '.cargo/bin'),
    path.join(homeDir, '.bun/bin'),
  ]
    .flatMap((entry) => entry.split(path.delimiter))
    .map((entry) => entry.trim())
    .filter(Boolean);
  return [...new Set(candidates)].join(path.delimiter);
}

function bridgePathEntries() {
  return bridgePath().split(path.delimiter).filter(Boolean);
}

function bridgeEnv() {
  const env = { ...process.env, PATH: bridgePath() };
  if (codexHome) env.CODEX_HOME = codexHome;
  return env;
}

function stripOuterQuotes(value) {
  const normalized = String(value || '').trim();
  if (
    normalized.length >= 2 &&
    ((normalized.startsWith('"') && normalized.endsWith('"')) ||
      (normalized.startsWith("'") && normalized.endsWith("'")))
  ) {
    return normalized.slice(1, -1);
  }
  return normalized;
}

async function fileExists(filePath) {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
}

async function resolveCodexCommand() {
  const raw = stripOuterQuotes(codexBin) || 'codex';
  const expanded = expandHomePath(raw);
  const hasPathSeparator = expanded.includes('/') || expanded.includes('\\');
  if (!isWindows || hasPathSeparator || path.extname(expanded)) {
    return expanded;
  }
  const names = [`${expanded}.cmd`, `${expanded}.exe`, `${expanded}.bat`, expanded];
  for (const entry of bridgePathEntries()) {
    for (const name of names) {
      const candidate = path.join(entry, name);
      if (await fileExists(candidate)) return candidate;
    }
  }
  return expanded;
}

function isAuthorized(req, payloadToken = '') {
  if (!token) return true;
  const header = req.headers.authorization || '';
  const bearer = header.startsWith('Bearer ') ? header.slice('Bearer '.length) : '';
  const custom = req.headers['x-omnibot-bridge-token'] || '';
  return token === bearer || token === custom || token === payloadToken;
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': jsonContentType,
    'content-length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

async function readJsonBody(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(Buffer.from(chunk));
  }
  const raw = Buffer.concat(chunks).toString('utf8').trim();
  if (!raw) return {};
  return JSON.parse(raw);
}

function isWildcardHost(value) {
  const normalized = String(value || '').trim().toLowerCase();
  return !normalized || normalized === '0.0.0.0' || normalized === '::' || normalized === '[::]';
}

function isLoopbackHost(value) {
  const normalized = String(value || '').trim().toLowerCase();
  return normalized === 'localhost' || normalized === '127.0.0.1' || normalized === '::1' || normalized === '[::1]';
}

function isPrivateIpv4(address) {
  const parts = address.split('.').map((part) => Number.parseInt(part, 10));
  if (parts.length !== 4 || parts.some((part) => Number.isNaN(part))) return false;
  const [a, b] = parts;
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

function isLinkLocalIpv4(address) {
  return address.startsWith('169.254.');
}

function networkInterfacePenalty(name) {
  const normalized = name.toLowerCase();
  if (/^(docker|br-|veth|vmnet|utun|awdl|llw|lo)/.test(normalized)) return 40;
  if (/(virtual|bridge|vmware|vbox|tailscale|zerotier)/.test(normalized)) return 25;
  return 0;
}

function listAdvertisableIpv4Addresses() {
  const entries = [];
  for (const [name, addresses] of Object.entries(os.networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family !== 'IPv4' || address.internal || !address.address) continue;
      if (isLoopbackHost(address.address) || isLinkLocalIpv4(address.address)) continue;
      const privateScore = isPrivateIpv4(address.address) ? 0 : 15;
      entries.push({
        name,
        address: address.address,
        score: privateScore + networkInterfacePenalty(name),
      });
    }
  }
  entries.sort((a, b) => a.score - b.score || a.name.localeCompare(b.name) || a.address.localeCompare(b.address));
  return entries;
}

function advertisedHosts() {
  const explicit = publicHost.trim();
  if (explicit) return [{ address: explicit, name: 'OMNIBOT_BRIDGE_PUBLIC_HOST' }];
  if (!isWildcardHost(host)) {
    return [{ address: host, name: 'OMNIBOT_BRIDGE_HOST' }];
  }
  const addresses = listAdvertisableIpv4Addresses();
  if (addresses.length > 0) return addresses;
  return [{ address: isWildcardHost(host) ? 'localhost' : host, name: 'fallback' }];
}

function hostForUrl(value) {
  const normalized = String(value || '').trim();
  return normalized.includes(':') && !normalized.startsWith('[') ? `[${normalized}]` : normalized;
}

function bridgeWebSocketUrl(advertisedHost) {
  return `ws://${hostForUrl(advertisedHost)}:${port}/codex`;
}

function quickConnectPayload(bridgeUrl) {
  const payload = new URL('omnibot://codex-bridge');
  payload.searchParams.set('bridgeUrl', bridgeUrl);
  payload.searchParams.set('cwd', bridgeCwd);
  if (token) payload.searchParams.set('token', token);
  return payload.toString();
}

async function readCodexVersion() {
  const resolvedCodexBin = await resolveCodexCommand();
  return new Promise((resolve) => {
    execFile(
      resolvedCodexBin,
      ['--version'],
      {
        cwd: bridgeCwd,
        env: bridgeEnv(),
        timeout: 5000,
        shell: isWindows,
        windowsHide: true,
      },
      (error, stdout, stderr) => {
        if (error) {
          resolve({
            ok: false,
            error: stderr?.trim() || error.message,
            resolvedCodexBin,
          });
          return;
        }
        resolve({ ok: true, version: stdout.trim(), resolvedCodexBin });
      }
    );
  });
}

function resolveDirectoryPath(rawPath = '') {
  const requested = String(rawPath || '').trim();
  if (!requested) return bridgeCwd;
  const expanded =
    requested === '~'
      ? homeDir
      : requested.startsWith('~/')
        ? path.join(homeDir, requested.slice(2))
        : requested;
  return path.isAbsolute(expanded)
    ? expanded
    : path.resolve(bridgeCwd, expanded);
}

function entryType(dirent) {
  if (dirent.isDirectory()) return 'directory';
  if (dirent.isFile()) return 'file';
  if (dirent.isSymbolicLink()) return 'symlink';
  return 'other';
}

function fileNameFromPath(filePath) {
  return path.basename(filePath);
}

function extensionFromPath(filePath) {
  return path.extname(filePath).toLowerCase();
}

function mimeTypeForPath(filePath) {
  const extension = extensionFromPath(filePath);
  const map = {
    '.aac': 'audio/aac',
    '.avi': 'video/x-msvideo',
    '.bmp': 'image/bmp',
    '.css': 'text/css',
    '.csv': 'text/csv',
    '.flac': 'audio/flac',
    '.gif': 'image/gif',
    '.htm': 'text/html',
    '.html': 'text/html',
    '.jpeg': 'image/jpeg',
    '.jpg': 'image/jpeg',
    '.js': 'text/javascript',
    '.json': 'application/json',
    '.jsonl': 'application/jsonl',
    '.m4a': 'audio/mp4',
    '.m4v': 'video/mp4',
    '.md': 'text/markdown',
    '.mov': 'video/quicktime',
    '.mp3': 'audio/mpeg',
    '.mp4': 'video/mp4',
    '.odp': 'application/vnd.oasis.opendocument.presentation',
    '.ods': 'application/vnd.oasis.opendocument.spreadsheet',
    '.odt': 'application/vnd.oasis.opendocument.text',
    '.ogg': 'audio/ogg',
    '.pdf': 'application/pdf',
    '.png': 'image/png',
    '.ppt': 'application/vnd.ms-powerpoint',
    '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    '.py': 'text/x-python',
    '.sh': 'application/x-sh',
    '.svg': 'image/svg+xml',
    '.ts': 'text/typescript',
    '.txt': 'text/plain',
    '.wav': 'audio/wav',
    '.webm': 'video/webm',
    '.webp': 'image/webp',
    '.xls': 'application/vnd.ms-excel',
    '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    '.xml': 'application/xml',
    '.yaml': 'application/yaml',
    '.yml': 'application/yaml',
  };
  return map[extension] || 'application/octet-stream';
}

function previewKindForPath(filePath, mimeType = mimeTypeForPath(filePath)) {
  const extension = extensionFromPath(filePath);
  if (mimeType.startsWith('image/')) return 'image';
  if (mimeType.startsWith('audio/')) return 'audio';
  if (mimeType.startsWith('video/')) return 'video';
  if (mimeType === 'application/pdf') return 'pdf';
  if (mimeType === 'text/html') return 'html';
  if (mimeType === 'text/markdown') return 'text';
  if (
    mimeType.startsWith('text/') ||
    [
      '.c',
      '.cc',
      '.cpp',
      '.dart',
      '.go',
      '.java',
      '.js',
      '.json',
      '.jsonl',
      '.kt',
      '.kts',
      '.mjs',
      '.py',
      '.rs',
      '.sh',
      '.sql',
      '.swift',
      '.toml',
      '.ts',
      '.tsx',
      '.xml',
      '.yaml',
      '.yml',
    ].includes(extension)
  ) {
    return 'code';
  }
  if (['.doc', '.docx', '.odt', '.rtf'].includes(extension)) return 'office_word';
  if (['.xls', '.xlsx', '.ods'].includes(extension)) return 'office_sheet';
  if (['.ppt', '.pptx', '.odp'].includes(extension)) return 'office_slide';
  return 'file';
}

function isProbablyText(buffer) {
  if (buffer.length === 0) return true;
  const sample = buffer.subarray(0, Math.min(buffer.length, 4096));
  let suspicious = 0;
  for (const byte of sample) {
    if (byte === 0) return false;
    if (byte < 7 || (byte > 14 && byte < 32)) suspicious += 1;
  }
  return suspicious / sample.length < 0.02;
}

async function listDirectory(rawPath = '') {
  const resolvedPath = resolveDirectoryPath(rawPath);
  const realPath = await fs.realpath(resolvedPath);
  const stat = await fs.stat(realPath);
  if (!stat.isDirectory()) {
    const error = new Error('path is not a directory');
    error.status = 400;
    throw error;
  }
  const dirents = await fs.readdir(realPath, { withFileTypes: true });
  const entries = dirents
    .map((dirent) => {
      const type = entryType(dirent);
      return {
        name: dirent.name,
        path: path.join(realPath, dirent.name),
        type,
        hidden: dirent.name.startsWith('.'),
      };
    })
    .sort((a, b) => {
      if (a.type === 'directory' && b.type !== 'directory') return -1;
      if (a.type !== 'directory' && b.type === 'directory') return 1;
      return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
    });
  const parent = path.dirname(realPath);
  return {
    ok: true,
    path: realPath,
    parent: parent === realPath ? null : parent,
    cwd: bridgeCwd,
    home: homeDir,
    entries,
  };
}

async function readFilePayload(rawPath = '') {
  const resolvedPath = resolveDirectoryPath(rawPath);
  const realPath = await fs.realpath(resolvedPath);
  const stat = await fs.stat(realPath);
  if (!stat.isFile()) {
    const error = new Error('path is not a file');
    error.status = 400;
    throw error;
  }
  if (Number.isFinite(maxReadBytes) && stat.size > maxReadBytes) {
    const mimeType = mimeTypeForPath(realPath);
    return {
      ok: true,
      path: realPath,
      name: fileNameFromPath(realPath),
      type: 'file',
      size: stat.size,
      mtimeMs: stat.mtimeMs,
      mimeType,
      previewKind: previewKindForPath(realPath, mimeType),
      truncated: true,
      error: `file is larger than OMNIBOT_BRIDGE_MAX_READ_BYTES (${maxReadBytes})`,
    };
  }
  const bytes = await fs.readFile(realPath);
  const mimeType = mimeTypeForPath(realPath);
  let previewKind = previewKindForPath(realPath, mimeType);
  const textLike = previewKind === 'text' || previewKind === 'code' || isProbablyText(bytes);
  if (textLike && previewKind === 'file') previewKind = 'text';
  return {
    ok: true,
    path: realPath,
    name: fileNameFromPath(realPath),
    type: 'file',
    size: stat.size,
    mtimeMs: stat.mtimeMs,
    mimeType,
    previewKind,
    encoding: textLike ? 'utf8' : 'base64',
    content: textLike ? bytes.toString('utf8') : undefined,
    dataBase64: textLike ? undefined : bytes.toString('base64'),
  };
}

async function writeTextFile(rawPath = '', content = '') {
  const resolvedPath = resolveDirectoryPath(rawPath);
  const realParent = await fs.realpath(path.dirname(resolvedPath));
  const targetPath = path.join(realParent, path.basename(resolvedPath));
  await fs.writeFile(targetPath, String(content), 'utf8');
  const stat = await fs.stat(targetPath);
  return {
    ok: true,
    path: targetPath,
    name: fileNameFromPath(targetPath),
    type: 'file',
    size: stat.size,
    mtimeMs: stat.mtimeMs,
  };
}

async function uploadAttachment(rawName = '', rawDataBase64 = '') {
  const name = path
    .basename(String(rawName || '').trim() || 'attachment')
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_');
  const dataBase64 = String(rawDataBase64 || '').replace(/\s+/g, '');
  if (!dataBase64) {
    const error = new Error('attachment data is required');
    error.status = 400;
    throw error;
  }
  const bytes = Buffer.from(dataBase64, 'base64');
  if (Number.isFinite(maxUploadBytes) && bytes.length > maxUploadBytes) {
    const error = new Error(
      `attachment is larger than OMNIBOT_BRIDGE_MAX_UPLOAD_BYTES (${maxUploadBytes})`
    );
    error.status = 413;
    throw error;
  }
  const attachmentDir = path.join(bridgeCwd, '.omnibot', 'attachments');
  await fs.mkdir(attachmentDir, { recursive: true });
  const targetPath = path.join(
    attachmentDir,
    `${Date.now()}_${crypto.randomUUID()}_${name}`
  );
  await fs.writeFile(targetPath, bytes);
  const stat = await fs.stat(targetPath);
  return {
    ok: true,
    path: targetPath,
    name,
    type: 'file',
    size: stat.size,
    mtimeMs: stat.mtimeMs,
  };
}

async function deletePath(rawPath = '', recursive = false) {
  const resolvedPath = resolveDirectoryPath(rawPath);
  const realPath = await fs.realpath(resolvedPath);
  const stat = await fs.stat(realPath);
  await fs.rm(realPath, { recursive: Boolean(recursive), force: false });
  return {
    ok: true,
    path: realPath,
    type: stat.isDirectory() ? 'directory' : 'file',
  };
}

async function movePath(rawPath = '', rawDestinationPath = '') {
  const sourcePath = await fs.realpath(resolveDirectoryPath(rawPath));
  const destinationResolved = resolveDirectoryPath(rawDestinationPath);
  const destinationParent = await fs.realpath(path.dirname(destinationResolved));
  const destinationPath = path.join(destinationParent, path.basename(destinationResolved));
  await fs.rename(sourcePath, destinationPath);
  const stat = await fs.stat(destinationPath);
  return {
    ok: true,
    path: destinationPath,
    name: fileNameFromPath(destinationPath),
    type: stat.isDirectory() ? 'directory' : 'file',
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (url.pathname === '/health') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    const version = await readCodexVersion();
    const transportReady = version.ok;
    sendJson(res, 200, {
      ok: transportReady,
      ready: transportReady,
      bridgeVersion: bridgePackage.version || null,
      codexVersion: version.version || null,
      codexBin,
      resolvedCodexBin: version.resolvedCodexBin || codexBin,
      acpTransport: 'stdio',
      transportReady,
      cwd: bridgeCwd,
      authRequired: Boolean(token),
      activeConnections,
      uptimeMs: Date.now() - bridgeStartedAt,
      platform: process.platform,
      error: version.error || null,
    });
    return;
  }

  if (url.pathname === '/fs/list') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const payload = await listDirectory(url.searchParams.get('path') || '');
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to list directory',
        path: url.searchParams.get('path') || bridgeCwd,
        cwd: bridgeCwd,
        home: homeDir,
      });
    }
    return;
  }

  if (url.pathname === '/fs/read') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const payload = await readFilePayload(url.searchParams.get('path') || '');
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to read file',
        path: url.searchParams.get('path') || '',
      });
    }
    return;
  }

  if (url.pathname === '/fs/write' && req.method === 'POST') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const body = await readJsonBody(req);
      const payload = await writeTextFile(body.path, body.content);
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to write file',
      });
    }
    return;
  }

  if (url.pathname === '/fs/upload' && req.method === 'POST') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const body = await readJsonBody(req);
      const payload = await uploadAttachment(body.name, body.dataBase64);
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to upload attachment',
      });
    }
    return;
  }

  if (url.pathname === '/fs/delete' && req.method === 'POST') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const body = await readJsonBody(req);
      const payload = await deletePath(body.path, body.recursive);
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to delete path',
      });
    }
    return;
  }

  if (url.pathname === '/fs/move' && req.method === 'POST') {
    if (!isAuthorized(req)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' });
      return;
    }
    try {
      const body = await readJsonBody(req);
      const payload = await movePath(body.path, body.destinationPath);
      sendJson(res, 200, payload);
    } catch (error) {
      sendJson(res, error.status || 500, {
        ok: false,
        error: error.message || 'failed to move path',
      });
    }
    return;
  }

  sendJson(res, 404, { ok: false, error: 'not_found' });
});

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (url.pathname !== '/codex') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', async (ws, req) => {
  let codex = null;
  let initialized = false;
  let connectionCounted = true;
  activeConnections += 1;

  function send(type, extra = {}) {
    if (ws.readyState === 1) {
      ws.send(JSON.stringify({ type, ...extra }));
    }
  }

  function closeCodex() {
    if (codex && !codex.killed) {
      if (isWindows && codex.pid) {
        const killer = spawn('taskkill', ['/pid', String(codex.pid), '/T', '/F'], {
          stdio: 'ignore',
          windowsHide: true,
        });
        killer.on('error', () => {});
      } else {
        codex.kill('SIGTERM');
      }
    }
    codex = null;
  }

  async function startStdioTransport(cwd, resolvedCodexBin) {
    codex = spawn(resolvedCodexBin, [], {
      cwd,
      env: bridgeEnv(),
      shell: isWindows,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    codex.stdout.setEncoding('utf8');
    codex.stderr.setEncoding('utf8');
    let stdoutBuffer = '';
    let stderrBuffer = '';
    const drainLines = (chunk, previous, type) => {
      const combined = previous + chunk;
      const lines = combined.split(/\r?\n/);
      const tail = lines.pop() || '';
      for (const line of lines) {
        if (line.trim()) send(type, { line });
      }
      return tail;
    };
    codex.stdout.on('data', (chunk) => {
      stdoutBuffer = drainLines(chunk, stdoutBuffer, 'stdout');
    });
    codex.stderr.on('data', (chunk) => {
      stderrBuffer = drainLines(chunk, stderrBuffer, 'stderr');
    });
    codex.on('exit', (code) => {
      if (stdoutBuffer.trim()) send('stdout', { line: stdoutBuffer });
      if (stderrBuffer.trim()) send('stderr', { line: stderrBuffer });
      codex = null;
      send('exit', { exitCode: code });
      ws.close(1011, 'codex exited');
    });
    codex.on('error', (error) => {
      codex = null;
      send('error', { message: error.message });
      ws.close(1011, 'codex failed');
    });
    send('hello', {
      ok: true,
      cwd,
      codexBin: resolvedCodexBin,
      transport: 'acp-stdio',
    });
  }

  async function handleMessage(data) {
    let message;
    try {
      message = JSON.parse(data.toString('utf8'));
    } catch (error) {
      send('error', { message: `invalid JSON: ${error.message}` });
      return;
    }

    if (!initialized) {
      if (message.type !== 'hello') {
        send('error', { message: 'hello is required before stdin' });
        ws.close(1008, 'hello required');
        return;
      }
      if (!isAuthorized(req, String(message.token || ''))) {
        send('hello', { ok: false, message: 'unauthorized' });
        ws.close(1008, 'unauthorized');
        return;
      }
      initialized = true;
      const cwd = String(message.cwd || bridgeCwd).trim() || bridgeCwd;
      const resolvedCodexBin = await resolveCodexCommand();
      await startStdioTransport(cwd, resolvedCodexBin);
      return;
    }

    if (message.type === 'stdin') {
      if (!codex || !codex.stdin.writable) {
        send('error', { message: 'ACP agent is not running' });
        return;
      }
      codex.stdin.write(`${String(message.line || '')}\n`);
      return;
    }

    if (message.type === 'close') {
      ws.close(1000, 'client close');
    }
  }

  ws.on('message', (data) => {
    handleMessage(data).catch((error) => {
      send('error', { message: error.message || 'bridge message handling failed' });
      ws.close(1011, 'bridge failed');
    });
  });

  function closeConnection() {
    if (connectionCounted) {
      connectionCounted = false;
      activeConnections = Math.max(0, activeConnections - 1);
    }
    closeCodex();
  }

  ws.on('close', closeConnection);
  ws.on('error', closeConnection);
});

server.listen(port, host);
await once(server, 'listening');
const advertised = advertisedHosts();
const primaryBridgeUrl = bridgeWebSocketUrl(advertised[0].address);
const payload = quickConnectPayload(primaryBridgeUrl);
console.log(`\n${color.heading('Omnibot Codex bridge')}`);
logField('Bridge version', bridgePackage.version || 'unknown', color.accent);
logField('Listening', `ws://${host}:${port}/codex`, color.green);
logField('Health check', `http://${host}:${port}/health`, color.prompt);
logField('Directory browser', `http://${host}:${port}/fs/list`, color.prompt);
logField('File browser API', `http://${host}:${port}/fs/read`, color.prompt);
logField('Attachment upload API', `http://${host}:${port}/fs/upload`, color.prompt);
logField('Working directory', bridgeCwd, color.white);
logField('Agent transport', 'ACP stdio', color.accent);
const startupCodexVersion = await readCodexVersion();
if (startupCodexVersion.ok) {
  logField('ACP agent', startupCodexVersion.version, color.green);
} else {
  console.log(color.warn(`ACP agent check failed: ${startupCodexVersion.error}`));
  console.log(color.dim('Install @agentclientprotocol/codex-acp or pass --acp-bin /absolute/path/to/codex-acp.'));
}
if (token) {
  logField('Token auth', 'enabled', color.warn);
  logField('Bridge token', token, color.warn);
}
logField('Quick connect URL', primaryBridgeUrl, color.green);
if (advertised.length > 1) {
  console.log(
    `${color.label('Other LAN addresses:')} ${color.dim(advertised
      .slice(1)
      .map((entry) => `${entry.address} (${entry.name})`)
      .join(', '))}`
  );
}
if (!publicHost.trim() && isWildcardHost(host)) {
  console.log(color.warn('Set OMNIBOT_BRIDGE_PUBLIC_HOST to override the QR address if this IP is not reachable from your phone.'));
}
if (!publicHost.trim() && isLoopbackHost(host)) {
  console.log(color.warn('OMNIBOT_BRIDGE_HOST is loopback; phones can only connect through adb reverse, a tunnel, or another forwarded network path.'));
}
logField('Quick connect payload', payload, color.dim);
qrcode.generate(payload, { small: true }, (qr) => {
  console.log(qr);
});
