#!/usr/bin/env bash
set -euo pipefail

# One executable JAR loads all property modules on one configured database.
if [[ "$#" -gt 1 ]]; then
  echo 'Usage: start-service.sh [application-local.yml]' >&2
  exit 2
fi
repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
service_jar="$repository_root/services/property-management-service/target/application.jar"
if [[ ! -f "$service_jar" ]]; then
  echo 'Missing JAR. Build first: mvn -pl :property-management-service -am verify' >&2
  exit 1
fi
if [[ -n "${1:-}" ]]; then
  if [[ ! -f "$1" ]]; then
    echo "Local configuration does not exist: $1" >&2
    exit 1
  fi
  local_config="$(cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")"
  set -- "--spring.config.additional-location=file:$local_config"
else
  set --
fi
java_command="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_command="$JAVA_HOME/bin/java"
fi
cd "$repository_root"
exec "$java_command" \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.scripting/javax.script=ALL-UNNAMED \
  -Dspring.reactor.debug-agent.enabled=false \
  -jar "$service_jar" "$@"
