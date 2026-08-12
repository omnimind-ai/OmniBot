# PRoot 32-bit loader source provenance

This directory contains only the source files required to build PRoot's ARM32
unbundled loader. It is vendored so Android release builds do not fetch or
execute source code from the network.

- Upstream: `https://github.com/termux/proot`
- Upstream version/tag: `v5.1.107.77`
- Git commit: `571a6c066639669ba7bef0cab9b70050c4fd60f5`
- Commit archive URL: `https://github.com/termux/proot/archive/571a6c066639669ba7bef0cab9b70050c4fd60f5.zip`
- Commit archive SHA-256: `1976a149c86e72c23d230dd6f467648cecb2516971fbd6b63c93d652db543ab8`
- License: GPL-2.0; the exact upstream `COPYING` file is included.

Vendored file SHA-256 values:

- `COPYING`: `078ba767b29d17dd2d31bc07ad3bc010ebd6359543ee326354b4133e1dcaae0e`
- `src/arch.h`: `396ef015b644ee4bc39400e90f363f5771e2d52bd1fbf158ea8be20566715b43`
- `src/attribute.h`: `4b8c8849fbd1e39dc3f7d9cbad60ac37992aa4c48a1912d17baf1801891cc146`
- `src/compat.h`: `558482b3026456a902a6ff4826d89547b974037811bac829d980e1cfab9a0858`
- `src/loader/loader.c`: `5de0e2cbc5a478b8cd25301cf1ccfc84e04a9b6197c4124fb3fd98a5166c9578`
- `src/loader/assembly.S`: `bf400d539aa118942ebdfdadbe63037852233a4c40364e085ff2d15656db441b`
- `src/loader/script.h`: `ec9df4d2ce2eacac15685242257101215b1c81deef598d0010e08a97f2f927d5`
- `src/loader/assembly-arm.h`: `3858ffe0a7a8c1dd6c6059f45136d1d48156237d5e743c19210a8f684277c077`

The Gradle build verifies these hashes, builds with the project-pinned Android
NDK, verifies the complete output hash and validates the ELF class, machine,
entry point, required `_start` symbol and every `PT_LOAD` segment before the
loader is packaged.
