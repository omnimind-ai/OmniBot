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
