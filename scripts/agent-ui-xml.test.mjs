import {test} from 'node:test';
import assert from 'node:assert/strict';
import {uiXmlField, hasComposerSelectionToolbar, hasAssistantReplyMarker} from './agent-ui-xml.mjs';

test('assistant assertions support exact multiword attachment results without accepting user echoes', () => {
  const result = 'OOB_IMAGE_123_COLORS: green, yellow';
  assert.equal(hasAssistantReplyMarker(`<node content-desc="${result}&#10;OOB_IMAGE_123_DONE"/>`, result), true);
  assert.equal(hasAssistantReplyMarker(`<node content-desc="编辑消息&#10;${result}"/>`, result), false);
  assert.equal(hasAssistantReplyMarker(`<node content-desc="prefix${result}"/>`, result), false);
  assert.equal(hasAssistantReplyMarker(`<node content-desc="OOB_IMAGE_123_COLORS: red, blue"/>`, result), false);
});
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

test('reply marker survives Markdown folding but never matches the user instruction or draft', () => {
  const marker = 'OOB_LIVE_PERF_DONE';
  for (const content of ['Done.&#10;'+marker, 'Done. '+marker, 'Done. '+marker+'&#10;47.9s&#10;11:43:50', marker]) {
    assert.equal(hasAssistantReplyMarker(`<node content-desc="${content}"/>`, marker), true);
  }
  for (const content of ['Reply '+marker, 'Do work. End your final reply with '+marker+'.',
      'Do work. End your final reply with '+marker, 'Done. prefix'+marker, marker+'_MORE']) {
    assert.equal(hasAssistantReplyMarker(`<node content-desc="${content}"/>`, marker), false);
  }
  assert.equal(hasAssistantReplyMarker(`<node class="android.widget.EditText" text="${marker}"/>`,marker),false);
});
