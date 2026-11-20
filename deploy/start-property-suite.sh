#!/usr/bin/env bash
set -euo pipefail
# SaaS public services are managed separately; this script runs the unified property backend.
deploy_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
compose() {
  docker compose --env-file "${PROPERTY_ENV_FILE:?Set PROPERTY_ENV_FILE to the unified service env file}" \
    -f "$deploy_root/compose.yml" "$@"
}
if [[ "$#" -gt 1 ]]; then
  echo 'Module selection is no longer supported; one service runs all property modules.' >&2
  exit 2
fi
case "${1:-status}" in
  check) compose config --quiet ;;
  start|update) compose up -d --no-build property-management ;;
  status) compose ps ;;
  *) echo 'Usage: start-property-suite.sh {check|start|update|status}' >&2; exit 2 ;;
esac
