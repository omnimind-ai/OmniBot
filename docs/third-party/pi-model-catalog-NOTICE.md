# Provider reasoning catalog provenance

`baselib/src/main/assets/provider_reasoning_catalog.json` contains a reduced
copy of model reasoning metadata from the pinned `@earendil-works/pi-ai`
**1.4.0** package. Its source is the Pi project's built-in model catalog:

- [Pi v1.4.0 source](https://github.com/earendil-works/pi/tree/v1.4.0/packages/ai)
- [Built-in catalog API](https://github.com/earendil-works/pi/blob/v1.4.0/packages/ai/src/providers/all.ts)
- [Upstream MIT license](https://github.com/earendil-works/pi/blob/v1.4.0/LICENSE)
- Local license copy: [pi-model-catalog-LICENSE.txt](pi-model-catalog-LICENSE.txt)

Generate or check the artifact without network access after installing the
repository's pinned dependencies:

```sh
node tools/pi-acp/scripts/generate-reasoning-catalog.mjs
node tools/pi-acp/scripts/generate-reasoning-catalog.mjs --check
```

The generator includes only selected official Provider routes and exact model
IDs. It copies the reasoning flag, explicitly declared standard wire effort
values from `thinkingLevelMap`, and an explicit `off` mapping when it proves
whether thinking can be disabled. Missing mappings remain unknown; generic
SDK fallback levels and defaults are not exported. An explicitly boolean
`compat.forceAdaptiveThinking` is copied as `reasoningSupportsAdaptive` so
request adapters preserve the declared thinking protocol. Explicit
`compat.thinkingFormat` and `compat.supportsReasoningEffort` declarations are
retained as `reasoningWireFormat` and `reasoningEffortSupported`, including
an explicit false value. The boolean
`compat.requiresReasoningContentOnAssistantMessages` is retained as
`reasoningContentRequired` for tool continuation. An explicit `off` mapping
to `none` or `off` also retains that exact wire value in the declared levels;
an absent mapping does not invent a disable value. Only supported Anthropic Messages, OpenAI Chat
Completions, Responses and the dedicated subscription route enter this
catalog. No prices, context limits,
input modalities, credentials, endpoints selected by users, or available-model
lists are replaced by this metadata.

At runtime, explicit Provider declarations take precedence. API protocol and
the full official route must match, with only equivalent root/v1 and endpoint
forms normalized. Model names, vendor prefixes, custom proxies, and URL host
substrings are not sufficient. ChatGPT OAuth uses its explicit Provider source
identity and the dedicated `openai-codex` catalog; a custom route pointing at a
similar URL does not acquire that identity.

## Reviewed Chat Completions endpoint aliases

Checked **2026-11-03** against the **Endpoint Support** section on the official
OpenAI model pages listed below. The generator copies reasoning metadata for
these 31 exact IDs from the pinned SDK's `openai-responses` entries into an
additional `openai-completions` entry at `https://api.openai.com/v1`. It retains
the Responses entries and does not add any model to a user's available-model
list or change the selected route.

- [gpt-4](https://developers.openai.com/api/docs/models/gpt-4.md),
  [gpt-4-turbo](https://developers.openai.com/api/docs/models/gpt-4-turbo.md),
  [gpt-4.1](https://developers.openai.com/api/docs/models/gpt-4.1.md),
  [gpt-4.1-mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini.md),
  [gpt-4.1-nano](https://developers.openai.com/api/docs/models/gpt-4.1-nano.md).
- [gpt-4o](https://developers.openai.com/api/docs/models/gpt-4o.md), including
  its `gpt-4o-2024-05-13`, `gpt-4o-2024-08-06`, and `gpt-4o-2024-11-20`
  snapshots on that same page;
  [gpt-4o-mini](https://developers.openai.com/api/docs/models/gpt-4o-mini.md).
- [gpt-5](https://developers.openai.com/api/docs/models/gpt-5.md),
  [gpt-5-chat-latest](https://developers.openai.com/api/docs/models/gpt-5-chat-latest.md),
  [gpt-5-mini](https://developers.openai.com/api/docs/models/gpt-5-mini.md),
  [gpt-5-nano](https://developers.openai.com/api/docs/models/gpt-5-nano.md),
  [gpt-5.1](https://developers.openai.com/api/docs/models/gpt-5.1.md),
  [gpt-5.2](https://developers.openai.com/api/docs/models/gpt-5.2.md).
- [gpt-5.4](https://developers.openai.com/api/docs/models/gpt-5.4.md),
  [gpt-5.4-mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini.md),
  [gpt-5.4-nano](https://developers.openai.com/api/docs/models/gpt-5.4-nano.md),
  [gpt-5.5](https://developers.openai.com/api/docs/models/gpt-5.5.md),
  [gpt-5.6-luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna.md),
  [gpt-5.6-sol](https://developers.openai.com/api/docs/models/gpt-5.6-sol.md),
  [gpt-5.6-terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra.md).
- [gpt-6-astra](https://developers.openai.com/api/docs/models/gpt-6-astra.md),
  [gpt-6-luna](https://developers.openai.com/api/docs/models/gpt-6-luna.md),
  [gpt-6-sol](https://developers.openai.com/api/docs/models/gpt-6-sol.md),
  [gpt-6.1-sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol.md).
- [o1](https://developers.openai.com/api/docs/models/o1.md),
  [o3](https://developers.openai.com/api/docs/models/o3.md),
  [o3-mini](https://developers.openai.com/api/docs/models/o3-mini.md),
  [o4-mini](https://developers.openai.com/api/docs/models/o4-mini.md).

This alias declares reasoning capabilities only. GPT-6 family tool calling
requires Responses; the catalog does not declare `toolCall` or upgrade an
existing Chat Completions selection. Unlisted models, including
`gpt-5.4-pro`, receive no Chat Completions alias.

Copyright (c) 2025 Mario Zechner. The derived catalog remains under the MIT
license reproduced alongside this notice.
