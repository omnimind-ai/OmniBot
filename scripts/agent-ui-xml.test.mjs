import {test} from 'node:test';
import assert from 'node:assert/strict';
import {uiXmlField, hasComposerSelectionToolbar} from './agent-ui-xml.mjs';
test('UIAutomator quote selection preserves completed reply markers', () => {
  assert.equal(uiXmlField(`<node content-desc='Search "Shape" succeeded.&#10;OOB_LIVE_DONE'/>`, 'content-desc'), 'Search "Shape" succeeded.\nOOB_LIVE_DONE');
  assert.equal(uiXmlField(`<node text="It&apos;s &lt;done&gt; &amp; &#x1f600;"/>`, 'text'), "It's <done> & 😀");
  assert.equal(uiXmlField('<node/>','text'),'');
});

test('composer selection popup is distinguished from message Copy buttons', () => {
  const input = '<node class="android.widget.EditText" focused="true"/>';
  const button = label => `<node content-desc="${label}" clickable="true" enabled="true"/>`;
  assert.equal(hasComposerSelectionToolbar([input,button('Select all'),button('Paste'),button('Send')]),true);
  assert.equal(hasComposerSelectionToolbar([input,button('全选'),button('粘贴')]),true);
  assert.equal(hasComposerSelectionToolbar([input,button('Copy'),button('Send')]),false);
  assert.equal(hasComposerSelectionToolbar([input,button('Select all'),button('Send')]),false);
  assert.equal(hasComposerSelectionToolbar([button('Select all'),button('Paste')]),false);
});
