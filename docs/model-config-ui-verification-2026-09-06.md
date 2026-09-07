# Compact model settings verification — 2026-09-06

The chat composer now uses one ACP configuration entry and the existing compact card/list components. The standalone Provider picker is not supplied by ChatPage. Configuration names are presentation labels; canonical option IDs and values still pass through readSession/setSessionConfigOption. Opening the card has zero transition duration; model rows omit vendor icons.

Focused Flutter checks: 40 panel/composer tests and 51 runtime service tests passed. Panel coverage includes reasoning selection, unknown options, authoritative write responses, repeated model refresh, running-state inspection, empty catalog refresh, and async disposal. Static analysis had no errors (existing warnings/info remain).

Phone inspection found: `Failed to initialize ACP agent Claude Code: Claude Code requires an Anthropic-compatible Provider endpoint`. The configured URL was https://llmapi.paratera.com. The adapter rejected the configured OpenAI protocol before launching the agent. This is evidence of a configuration mismatch, not proof that this service cannot expose Anthropic Messages. No credential was printed, no endpoint was guessed, and the compatibility guard remains. The user-facing error now explains the mismatch in Chinese.

Remaining: prove the service/account supports an Anthropic Messages endpoint before changing its protocol/address. An advertised model catalog alone does not prove a successful authorized inference. External Harness catalog filtering and refresh must stay with its existing adapter/session owner; this UI change does not establish that every advertised option is callable.
