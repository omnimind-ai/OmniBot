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

The website repository owns the only hourly catalog workflow after migration.
This Android workflow has `workflow_dispatch` only, so merging this branch cannot
start a second hourly publisher.

Deploy and validate the website backend first, configure the website repository's
`APP_UPDATE_WORKER_TOKEN` to match its `ADMIN_TOKEN`, then set website repository
variable `OMNIBOT_BACKEND_READY=true`. Both website scheduled and manual runs
require that readiness switch. Manually dispatch the website workflow and verify
publishing plus its public mirror check before disabling the old Android hourly
workflow. The Android default branch still writes directly to R2 until that old
workflow is disabled or this manual-only change reaches the default branch;
changing the Worker to a compatibility proxy does not stop direct R2 writes.

The website URL defaults to `https://omnibot.omnimind.com.cn`; an optional
`OMNIBOT_BACKEND_URL` repository variable overrides it. No R2/AWS secrets are
needed by the website scheduler. Keep this Android catalog workflow manual-only
when this feature branch is merged.

## Scope

This change replaces the product Worker service. Account and AI gateway
services (`account.omnimind.com.cn`, `model-api.omnimind.com.cn`), image
providers, and user-configured Cloudflare/model proxies keep their configured
endpoints because they are independent services.
