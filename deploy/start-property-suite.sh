#!/usr/bin/env bash
set -euo pipefail
cd "${PROPERTY_SUITE_DIR:-/opt/jetlinks-saas-test}"

cloud() { docker compose --env-file .env -f docker-compose.yml -f docker-compose.release.yml "$@"; }
property() { docker compose --env-file .env -f docker-compose.property.yml "$@"; }
patrol() { docker compose --env-file .env --env-file .env.patrol -f docker-compose.patrol.yml "$@"; }
frontend() { docker compose --env-file .env --env-file .env.frontend -f docker-compose.frontend.yml "$@"; }
business() { docker compose --env-file .env -f docker-compose.business-web.yml "$@"; }
reload_proxies() {
  local container
  for container in saas-test-frontend saas-test-repair-web saas-test-charging-web saas-test-repair-h5 saas-test-patrol-h5; do
    if [ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null || true)" != true ]; then
      continue
    fi
    docker exec "$container" nginx -t
    docker exec "$container" nginx -s reload
  done
}

action="${1:-status}"
case "$action" in
  check)
    for group in cloud property patrol frontend business; do "$group" config --quiet; done
    ;;
  start)
    shift
    applications=""
    for application in "$@"; do
      case "$application" in
        repair|patrol|finance) applications="$applications $application" ;;
        all) applications="repair patrol finance" ;;
        *) printf '%s\n' 'Applications: repair, patrol, finance, all' >&2; exit 2 ;;
      esac
    done
    cloud up -d --no-build --wait --wait-timeout 900
    # Base is a separate mandatory service. Selecting an app never starts a sibling app.
    property up -d --no-build --wait --wait-timeout 600 base
    case " $applications " in *' repair '*)
      property up -d --no-build --wait --wait-timeout 600 repair
      business up -d --no-build --wait --wait-timeout 120 repair-web repair-h5 ;;
    esac
    case " $applications " in *' patrol '*)
      patrol up -d --no-build --wait --wait-timeout 600 patrol
      business up -d --no-build --wait --wait-timeout 120 patrol-h5 ;;
    esac
    case " $applications " in *' finance '*)
      property up -d --no-build --wait --wait-timeout 600 finance
      business up -d --no-build --wait --wait-timeout 120 charging-web ;;
    esac
    frontend up -d --no-build --wait --wait-timeout 120
    reload_proxies
    ;;
  update)
    group="${2:?Specify cloud, property, patrol, frontend or business}"
    case "$group" in cloud|property|patrol|frontend|business) ;; *) exit 2 ;; esac
    shift 2
    if [ "$group" = cloud ] && [ "$#" -eq 0 ]; then
      printf '%s\n' 'Specify a Cloud service explicitly; see PROPERTY-SUITE-OPERATIONS.md.' >&2
      exit 2
    fi
    case "$group" in property|patrol)
      property up -d --no-build --wait --wait-timeout 600 base ;;
    esac
    "$group" up -d --no-build --wait --wait-timeout 900 "$@"
    reload_proxies
    ;;
  reload-proxies) reload_proxies ;;
  status)
    for group in cloud property patrol frontend business; do "$group" ps; done
    ;;
  *) printf '%s\n' 'Usage: start-property-suite.sh {check|start [repair|patrol|finance|all ...]|status|reload-proxies|update GROUP [SERVICE...]}' >&2; exit 2 ;;
esac
