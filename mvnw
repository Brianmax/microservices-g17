#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
distribution_url=$(sed -n 's/^distributionUrl=//p' "$project_dir/.mvn/wrapper/maven-wrapper.properties")
maven_version=$(printf '%s' "$distribution_url" | sed -n 's#.*apache-maven/\([^/]*\)/.*#\1#p')
maven_home="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/apache-maven-$maven_version"
maven_command="$maven_home/apache-maven-$maven_version/bin/mvn"

if [ ! -x "$maven_command" ]; then
  archive="$maven_home/apache-maven-$maven_version-bin.zip"
  mkdir -p "$maven_home"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$distribution_url" -o "$archive"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$distribution_url" -O "$archive"
  else
    echo "curl or wget is required to download Maven" >&2
    exit 1
  fi
  unzip -q "$archive" -d "$maven_home"
  rm -f "$archive"
fi

exec "$maven_command" "$@"
