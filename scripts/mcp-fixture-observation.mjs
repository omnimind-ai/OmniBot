import assert from 'node:assert/strict';
export function assertMcpFixturePhase(observation, baseline, phase) {
  const events=observation.events.slice(baseline);
  const calls=events.filter(e=>e.method==='tools/call');
  if(phase==='denied' || phase==='denied-recovery') {
    const denials=calls.filter(e=>e.tool==='lifecycle_denied');
    assert.equal(denials.length,1,'Expected one denied tool, no replay');
    if(phase==='denied-recovery') assert.equal(calls.filter(e=>e.tool==='lifecycle_echo').length,1,'Expected one recovery echo');
    return {phase,requestId:denials[0].id,passed:true,eventCount:events.length};
  }
  const waits=calls.filter(e=>e.tool==='lifecycle_wait');
  assert.equal(waits.length,1,'Expected one admitted wait tool, no replay');
  const requestId=waits[0].id;
  if(phase==='pending') assert(observation.pending.includes(requestId),'Wait tool not pending');
  else {
    const cancelled=events.filter(e=>e.cancelled===requestId);
    assert.equal(cancelled.length,1,'Expected one cancellation for original request');
    assert.equal(cancelled[0].found,true,'Remote operation was not active');
    if(waits[0].streamId != null) assert.equal(cancelled[0].streamId,waits[0].streamId,'Cancellation used another SSE endpoint');
    assert(!observation.pending.includes(requestId),'Remote operation remains pending');
    if(phase==='echo') assert.equal(calls.filter(e=>e.tool==='lifecycle_echo').length,1,'Expected one subsequent echo call');
    else assert.equal(phase,'cancelled','Unknown MCP fixture phase');
  }
  return {phase,requestId,passed:true,eventCount:events.length};
}
