#!/bin/sh
set -eu
root=$(pwd)
export PYTHONPATH="${OMNIFLOW_SOURCE_OVERRIDE:+$OMNIFLOW_SOURCE_OVERRIDE:}$root/scripts/runtime/python:$root/vendor/site-packages:$root/scripts/runtime/.runtime/omnitransfer/src"
export OMNITRANSFER_ROOT="$root/scripts/runtime/.runtime/omnitransfer"
checkpoint=$(sed -n 's/^omnitransfer.checkpoint=//p' scripts/runtime/runtime.properties)
export OMNITRANSFER_MATCHER_CHECKPOINT="$OMNITRANSFER_ROOT/src/omnitransfer/$checkpoint"
# Keep the existing data location across this host refactor. Only the package knows its format.
exec python3 -u -m omniflow.bridge --store /workspace/.omnibot/omniflow/omniflow.json
