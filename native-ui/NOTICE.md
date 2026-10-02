# Icon attribution

Except for `omni_menu.xml`, `omni_settings.xml`, and `omni_brand_*.xml` (existing OmniBot asset geometry), the `omni_*.xml` vector drawables reproduce Lucide 0.468.0 SVG geometry from https://github.com/lucide-icons/lucide/tree/0.468.0/icons . The preference actions also use pin, pin-off, pencil, sparkles, globe, play, notebook-pen, house, power, eye-off, vibrate, corner-down-left, hand, graduation-cap, move-left and image from the same repository (main, retrieved 2026-09-23); the alarm and open-with pages additionally use check and file from the same release, and the remote Bridge page uses scan-qr-code and radio-tower; the scheduled-tasks page uses clock and timer; the 轨迹 (usage statistics) page uses message-circle, flame, zap, network and refresh-ccw; the skill store page uses hard-drive-download (from the existing `ui/assets/home/hard_drive_download.svg`) and badge-check (from the inline SVG in the Flutter skill store page); the plugin pages use route, send and circle-check from the same release; trash-2 reuses the existing ReTerminal vector. They use the same icon family as the Flutter UI.

ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2022.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

The brand vectors preserve the existing `ui/assets/provider_icons/moonshot.svg` and `deepseek.svg` paths, attributed to Lobe Icons in `ui/lib/widgets/agent_brand_icon.dart` (https://github.com/lobehub/lobe-icons). They use the same blue tint as the Flutter widget.

The Agent brand vectors `omni_brand_codex.xml`, `omni_brand_claude.xml` and `omni_brand_opencode.xml` preserve the existing `ui/assets/agents/codex.svg`, `claude_code.svg` and `opencode.svg` geometry from the same Lobe Icons attribution, with the same tint rules as the Flutter widget.

`omni_memory_context.xml` preserves the existing `ui/assets/memory/memory_context_icon.svg` geometry (OmniBot asset).

`omni_default_pet.png` is copied from this repository's existing `ui/assets/avatar/default_avatar1.png` so the native pet picker shows the same built-in preview.

`omni_about_logo.png` is an unchanged copy of OmniBot's existing `ui/assets/my/about_icon.png`.
