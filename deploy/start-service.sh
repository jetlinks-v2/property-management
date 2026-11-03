#!/usr/bin/env bash
set -euo pipefail

# Each executable JAR contains only its own business system and platform libraries.
system_name="${1:-patrol}"
case "$system_name" in
  base|finance|patrol) ;;
  *) echo 'Usage: start-service.sh {base|finance|patrol} [application-local.yml]' >&2; exit 2 ;;
esac
repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
service_jar="$repository_root/services/property-$system_name-service/target/application.jar"
if [[ ! -f "$service_jar" ]]; then
  echo "Missing JAR. Build first: mvn -pl :property-$system_name-service -am verify" >&2
  exit 1
fi
if [[ -n "${2:-}" ]]; then
  if [[ ! -f "$2" ]]; then
    echo "Local configuration does not exist: $2" >&2
    exit 1
  fi
  local_config="$(cd -- "$(dirname -- "$2")" && pwd)/$(basename -- "$2")"
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
