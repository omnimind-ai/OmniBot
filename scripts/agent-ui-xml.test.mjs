import {test} from 'node:test';
import assert from 'node:assert/strict';
import {uiXmlField} from './agent-ui-xml.mjs';
test('UIAutomator quote selection preserves completed reply markers', () => {
  assert.equal(uiXmlField(`<node content-desc='Search "Shape" succeeded.&#10;OOB_LIVE_DONE'/>`, 'content-desc'), 'Search "Shape" succeeded.\nOOB_LIVE_DONE');
  assert.equal(uiXmlField(`<node text="It&apos;s &lt;done&gt; &amp; &#x1f600;"/>`, 'text'), "It's <done> & 😀");
  assert.equal(uiXmlField('<node/>','text'),'');
});
