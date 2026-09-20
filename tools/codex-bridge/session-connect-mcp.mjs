#!/usr/bin/env node
import {readFile} from 'node:fs/promises';
import {Server} from '@modelcontextprotocol/sdk/server/index.js';
import {StdioServerTransport} from '@modelcontextprotocol/sdk/server/stdio.js';
import {CallToolRequestSchema, ListToolsRequestSchema} from '@modelcontextprotocol/sdk/types.js';
import {prepareSessionConnection} from './session-connect.mjs';

const configPath = process.env.OMNIBOT_CONNECT_CONFIG;
if (!configPath) throw Error('OMNIBOT_CONNECT_CONFIG must name a private configuration file');
const config = JSON.parse(await readFile(configPath, 'utf8'));
const server = new Server({name: 'omnibot-session-connect', version: '0.1.0'},
  {capabilities: {tools: {}}});
server.setRequestHandler(ListToolsRequestSchema, async () => ({tools: [{
  name: 'connect_session_to_xiaowan',
  description: 'Prepare a private QR to open a specified running Codex session in Xiaowan. Use the current session ID from trusted host context when the user asks for this conversation. Never guess an ID. Requires the session to already run on the configured Bridge backend; does not migrate or replay it. Returns a local PNG path, never credential text.',
  inputSchema: {type: 'object', properties: {sessionId: {type: 'string', minLength: 1}},
    required: ['sessionId'], additionalProperties: false},
}]}));
server.setRequestHandler(CallToolRequestSchema, async request => {
  if (request.params.name !== 'connect_session_to_xiaowan') {
    return {isError: true, content: [{type: 'text', text: 'Unknown tool'}]};
  }
  try {
    const result = await prepareSessionConnection(config, request.params.arguments?.sessionId);
    return {isError: !result.ok, content: [{type: 'text', text: JSON.stringify(result)}]};
  } catch {
    return {isError: true, content: [{type: 'text', text: 'Unable to prepare connection. Check the configured backend and private credential files.'}]};
  }
});
await server.connect(new StdioServerTransport());
