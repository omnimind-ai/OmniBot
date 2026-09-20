// UIAutomator may choose either quote delimiter for an XML attribute.
export function uiXmlField(node, key) {
  const value = node.match(new RegExp(`${key}=(["'])([\\s\\S]*?)\\1`))?.[2] || '';
  return value.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (_, entity) => {
    const named = {amp:'&',lt:'<',gt:'>',quot:'"',apos:"'"};
    if (entity[0] !== '#') return named[entity];
    return String.fromCodePoint(entity[1].toLowerCase() === 'x'
      ? parseInt(entity.slice(2),16) : parseInt(entity.slice(1),10));
  });
}

// Floating text-selection controls can consume an outside tap even while Send is enabled.
export function hasComposerSelectionToolbar(nodes) {
  if (!nodes.some(n => uiXmlField(n, 'class') === 'android.widget.EditText' && uiXmlField(n, 'focused') === 'true')) return false;
  const labels = nodes.filter(n => uiXmlField(n,'clickable') === 'true' && uiXmlField(n,'enabled') === 'true')
    .map(n => uiXmlField(n,'content-desc') || uiXmlField(n,'text'));
  return labels.some(label => ['Select all','全选'].includes(label)) &&
    labels.some(label => ['Paste','粘贴','Copy','复制','Cut','剪切'].includes(label));
}

// Markdown may fold a single newline into a space in accessibility text.
// A unique final marker still counts, but never the composer or the test's user instruction.
export function hasAssistantReplyMarker(node, marker) {
  if (uiXmlField(node, 'class') === 'android.widget.EditText') return false;
  const text = uiXmlField(node, 'content-desc') || uiXmlField(node, 'text');
  if (text.includes(`Reply ${marker}`) || text.includes(`End your final reply with ${marker}`) ||
      /^(编辑消息|Edit message)(\n|$)/.test(text)) return false;
  return text.split('\n').some(line => {
    const value = line.trimEnd();
    return value.endsWith(marker) &&
      (value.length === marker.length || /\s/.test(value[value.length - marker.length - 1]));
  });
}
