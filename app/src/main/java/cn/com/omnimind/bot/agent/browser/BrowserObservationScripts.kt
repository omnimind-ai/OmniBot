package cn.com.omnimind.bot.agent

/** Model-requested browser observations must preserve every selected fact. */
internal object BrowserObservationScripts {
    fun scrollAndCollect(explicitSelectorLiteral: String): String =
        """
            const explicitSelector = $explicitSelectorLiteral;
            const selector = explicitSelector || detectCollectionSelector();
            const nodes = selector ? Array.from(document.querySelectorAll(selector)).filter(isVisible) : [];
            const items = nodes.map(node => normalizeText(node.innerText || node.textContent || ''))
                .filter(Boolean);
            return {
                selectorUsed: selector,
                items: items
            };
        """.trimIndent()

    fun findElements(selectorLiteral: String): String =
        """
            const selector = $selectorLiteral;
            const nodes = Array.from(document.querySelectorAll(selector)).filter(isVisible);
            return nodes.map(node => describeElement(node));
        """.trimIndent()

    fun backbone(maxDepth: Int): String =
        """
            function buildBackbone(node, depth) {
                if (!node || depth > $maxDepth) return null;
                const children = Array.from(node.children || [])
                    .map(child => buildBackbone(child, depth + 1))
                    .filter(Boolean);
                return {
                    tag: (node.tagName || '').toLowerCase(),
                    id: node.id || null,
                    classes: Array.from(node.classList || []),
                    role: node.getAttribute ? node.getAttribute('role') : null,
                    text: normalizeText(node.innerText || node.textContent || ''),
                    interactive: isInteractive(node),
                    children: children
                };
            }
            return buildBackbone(document.body, 0);
        """.trimIndent()
}
