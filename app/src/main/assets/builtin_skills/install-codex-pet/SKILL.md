---
name: install-codex-pet
description: Handle requests for shared Codex pet packages. The network installer is fail-closed until the codex-pets dependency graph is shipped with a reviewed lock and integrity manifest.
---

# Install Codex Pet

Install pets into Omnibot's selectable pet directory while preserving the
official Codex package format.

## Workflow

1. Extract the lowercase pet slug or collection slug from the request. Do not
   guess when no slug is present.
2. Confirm `node` and `npm` are available. If they are missing, report that the
   embedded {{OMNIBOT_TERMINAL_DISTRIBUTION}} environment needs its base packages installed.
3. Do not execute a network package runner. Report
   the stable code below and explain that this formal build will not download
   an unaudited package graph:

```sh
printf '%s\n' 'AGENT_RUNTIME_CODEX_PETS_LOCK_REQUIRED'
```

4. If a pet was installed previously, it may still be validated locally:

```sh
sh /workspace/.omnibot/skills/install-codex-pet/scripts/validate_codex_pet.sh claude-pixel
```

5. For a pre-existing valid pet, report the installed display name and
   `/workspace/.omnibot/pets/<pet-id>/`. The appearance page discovers the
   package automatically; do not copy it into another pet directory.

## Rules

- Keep `CODEX_HOME=/workspace/.omnibot` for every `codex-pets` command. The
  package defaults to `/root/.codex`, which the Omnibot appearance scanner does
  not use.
- Never use an npm network runner until this skill ships an audited exact
  version, complete transitive lock, and integrity manifest. Do not generate
  replacement art, rewrite a downloaded manifest, or hand-build a package.
- Accept only lowercase slugs made from letters, digits, and hyphens.
- Treat command failures, missing `pet.json`, missing `spritesheet.webp`, an id
  mismatch, or an unexpected `spritesheetPath` as installation failures.
- Never expose credentials or add API keys to commands. Alternate registries
  and `CODEX_PETS_API_BASE` do not bypass the lock requirement.
