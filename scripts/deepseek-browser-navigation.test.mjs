import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
const installer=readFileSync('app/src/main/assets/acp/install/deepseek-harness.sh','utf8');
const patch=installer.split("node <<'OMNIBOT_DSH_BROWSER_NAVIGATION'\n")[1].split('\nOMNIBOT_DSH_BROWSER_NAVIGATION')[0];
const original=`if (validToken) {\n\t\t\t\tres.writeHead(303, {
  "cache-control": "no-store",
  "location": "/",
  "referrer-policy": "no-referrer",
  "set-cookie": sessionCookie(cookieName(authority), value, expiresAt, maxAge)
});
res.end();
\t\t\t\treturn false;
}
writeUnauthorized(req, res);
`;
function fixture(source, run) {
 const root=mkdtempSync(join(tmpdir(),'oob-dsh-navigation-')); const file=join(root,'index.js');
 try {
  writeFileSync(file,source);
  const apply=()=>spawnSync(process.execPath,['-e',patch.replace(/const path = .*;/,`const path = ${JSON.stringify(file)};`)],{encoding:'utf8'});
  run(file,apply);
 } finally {rmSync(root,{recursive:true,force:true});}
}
test('verified login establishes same-origin document while retaining auth headers and checks',()=>{
 fixture(original,(file,apply)=>{
  assert.equal(apply().status,0);
  const result=readFileSync(file,'utf8');
  assert(result.includes('res.writeHead(200'));
  for(const required of ['if (validToken)', '"cache-control": "no-store"', '"referrer-policy": "no-referrer"', '"set-cookie": sessionCookie(', 'writeUnauthorized(req, res);']) assert(result.includes(required));
  assert(result.includes('<meta http-equiv="refresh" content="0;url=/">'));
  assert(!result.includes('SameSite=Lax'));
  assert.equal(apply().status,0);
  assert.equal(readFileSync(file,'utf8'),result);
 });
});
test('unrecognized upstream exchange fails without modifying the file',()=>{
 for(const invalid of ['unexpected implementation',original.replace('"set-cookie": sessionCookie(', '"set-cookie": anotherCookie(')]) fixture(invalid,(file,apply)=>{
  assert.notEqual(apply().status,0);
  assert.equal(readFileSync(file,'utf8'),invalid);
 });
});
