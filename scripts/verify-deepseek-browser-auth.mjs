// Exercise the installed official BrowserAuth implementation with the Android
// navigation compatibility patch. No credentials or launch tokens are logged.
// Usage: node scripts/verify-deepseek-browser-auth.mjs /path/to/dsh-client-connection/lib/index.js
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,rmSync} from 'node:fs';
import {randomBytes} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import http from 'node:http';
import {once} from 'node:events';
const original=process.argv[2]; assert(original?.endsWith('/lib/index.js'));
const file=original.replace(/index.js$/,`oob-auth-test-${process.pid}.mjs`);
let server;
try {
 writeFileSync(file,readFileSync(original,'utf8')+'\nexport { BrowserAuth };\n');
 const installer=readFileSync('app/src/main/assets/acp/install/deepseek-harness.sh','utf8');
 const patch=installer.split("node <<'OMNIBOT_DSH_BROWSER_NAVIGATION'\n")[1].split('\nOMNIBOT_DSH_BROWSER_NAVIGATION')[0];
 assert.equal(spawnSync(process.execPath,['-e',patch.replace(/const path = .*;/,`const path = ${JSON.stringify(file)};`)],{stdio:'pipe'}).status,0);
 const {BrowserAuth}=await import(pathToFileURL(file));
 const auth=new BrowserAuth({},randomBytes(32),30);
 server=http.createServer((req,res)=>{if(auth.authorizeIndex(req,res))res.end('AUTHENTICATED');});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const origin=`http://127.0.0.1:${server.address().port}`;
 assert.equal((await fetch(origin)).status,401);
 assert.equal((await fetch(origin+'/?token=invalid')).status,401);
 const login=await fetch(auth.authenticatedUrl(origin),{redirect:'manual'});
 assert.equal(login.status,200);
 assert((await login.text()).includes('content="0;url=/"'));
 const cookie=login.headers.get('set-cookie');
 assert(cookie.includes('HttpOnly')&&cookie.includes('SameSite=Strict'));
 assert.equal(login.headers.get('referrer-policy'),'no-referrer');
 assert.equal(login.headers.get('cache-control'),'no-store');
 const index=await fetch(origin,{headers:{Cookie:cookie.split(';')[0]}});
 assert.equal(await index.text(),'AUTHENTICATED');
 assert.equal((await fetch(origin,{headers:{Cookie:cookie.split(';')[0]+'tampered'}})).status,401);
 console.log(JSON.stringify({officialImplementation:true,validLogin:200,unauthorized:401,tamperedCookie:401,strictHttpOnly:true,cookieAuthenticatedIndex:true}));
} finally {server?.closeAllConnections();server?.close();rmSync(file,{force:true});}
