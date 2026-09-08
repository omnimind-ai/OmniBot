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
