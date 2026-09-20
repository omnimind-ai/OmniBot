#!/usr/bin/env bash
# Install a reviewed Nginx candidate, validate, then reload; restore on failure.
set -euo pipefail
site=${1:?existing site path required}
candidate=${2:?reviewed candidate path required}
expected=${3:?expected current site SHA256 required}
[[ $EUID == 0 ]] || { echo 'Run with sudo on the website host.' >&2; exit 1; }
[[ -f "$site" && ! -L "$site" && -f "$candidate" ]] || exit 1
actual=$(sha256sum "$site")
[[ ${actual%% *} == "$expected" ]] || {
  echo 'Website configuration changed; review the new diff before deployment.' >&2
  exit 1
}
backup=$(mktemp "${site}.backup.XXXXXXXX")
cp --preserve=mode,ownership "$site" "$backup"
rollback() {
  cp --preserve=mode,ownership "$backup" "$site"
  /usr/sbin/nginx -t && /usr/bin/systemctl reload nginx ||
    echo 'Original file restored, but Nginx recovery reload requires attention.' >&2
  echo "Restored original website configuration; backup: $backup" >&2
}
trap rollback ERR
install -o root -g root -m 644 "$candidate" "$site"
/usr/sbin/nginx -t
/usr/bin/systemctl reload nginx
trap - ERR
echo "Installed and reloaded website route; original backup: $backup"
