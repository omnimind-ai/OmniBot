# Website backend integration

The first-party service defaults to `https://omnibot.omnimind.com.cn`.
The Android build keeps `OMNIBOT_UPDATE_WORKER_URL` and the `worker` download
source value for backwards compatibility, while the selected download source
is labelled “小万官网” / “Omnibot website”.

- `/updates`: release checks, anonymous installation/device statistics, cloud
  service policy, and official VLM operation configuration.
- `/downloads/:tag/:asset`: APK delivery; cached downloads are reconstructed
  with the current backend URL when reading update state.
- `/catalog/models-dev/api.json`: model catalog mirror. Existing device catalog
  caches and their stale-cache fallback are retained.
- `/feedback/?source=android&lang=zh|en`: shared feedback form. Both Settings
  feedback and About → Send feedback open the browser to support the Android
  file picker. Only app version metadata is attached automatically.
- The official VLM operation cache migrates the old product Worker origin to
  the website origin and preserves the URL path. Custom model Provider profiles
  are separate and are not rewritten.

## Publishing

GitHub release and manual catalog workflows use `vars.OMNIBOT_BACKEND_URL`,
falling back to the website. They intentionally do not use the old
`secrets.APP_UPDATE_WORKER_URL`; update its replacement variable for a custom
backend. `secrets.APP_UPDATE_WORKER_TOKEN` remains the admin API bearer token;
its value must match the new backend administrator token.

The existing local release command accepts `OMNIBOT_BACKEND_URL` or
`--worker-url`. The legacy `APP_UPDATE_WORKER_URL` override remains available
for explicit compatibility workflows.

The catalog synchronization command validates the complete upstream catalog,
including size, required providers, counts, and suspicious count drops. It
publishes raw JSON to `/api/admin/models-dev` with hash and freshness headers.
The backend stores snapshots and current metadata. Conditional upstream `304`
and failures update `/api/admin/models-dev/status` while preserving current
catalog data. The CLI defaults to this mode and requires
`OMNIBOT_BACKEND_TOKEN` or `APP_UPDATE_WORKER_TOKEN`.

The old R2 command is retained only as an explicit recovery path:
`MODELS_DEV_STORAGE=r2 node scripts/sync_models_dev_catalog.mjs`.
It requires the previous R2 credentials and is not used by CI.

## Catalog scheduler cutover

The production server `4090` owns the only hourly catalog publisher after
migration: `omnibot-catalog-sync.timer` runs at minute 37 of every hour, catches
up after downtime with `Persistent=true`, and checks again five minutes after
boot. Its oneshot service runs as `sy` from the current website backend release
and maps the existing private environment's `ADMIN_TOKEN` into the shared CLI;
no token is duplicated in the repository or in a second secret file.

Deploy and validate the website backend first, then run the website repository's
`deploy/pa2/install-catalog-sync.sh`. The installer backs up existing units and
timer enable/active state, runs a real sync before enabling the timer, and restores
the previous configuration if installation or synchronization fails. It never
stops the website backend. Verify service logs, published SHA-256, public catalog,
and the timer's next activation before disabling the old Android R2 hourly
workflow. Changing the Worker to a compatibility proxy does not stop direct R2
writes from the old Android default-branch workflow.

Both website and Android GitHub catalog workflows are manual-only recovery
options. This Android workflow has `workflow_dispatch` only, so merging this
branch cannot start a second hourly publisher. The website manual workflow also
requires `OMNIBOT_BACKEND_READY=true` and `APP_UPDATE_WORKER_TOKEN` matching the
backend's `ADMIN_TOKEN`. GitHub Actions organization billing currently prevents
its jobs from starting; the production timer does not depend on Actions billing.

The website URL defaults to `https://omnibot.omnimind.com.cn`; an optional
`OMNIBOT_BACKEND_URL` configuration overrides it. No R2/AWS secrets are needed by
the production scheduler. Keep both repository workflows manual-only when this
feature branch is merged.

## Scope

This change replaces the product Worker service. Account and AI gateway
services (`account.omnimind.com.cn`, `model-api.omnimind.com.cn`), image
providers, and user-configured Cloudflare/model proxies keep their configured
endpoints because they are independent services.
