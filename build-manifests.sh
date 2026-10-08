#!/bin/bash
set -euo pipefail

branch=${1:-main}
if [[ ! "$branch" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,119}$ ]]; then
  echo "Invalid container image tag" >&2
  exit 2
fi

while IFS= read -r manifest || [[ -n "$manifest" ]]; do
  [[ -z "$manifest" || "$manifest" == \#* ]] && continue
  image=${manifest%:*}
  aarch64_image="${image}:${branch}-aarch64"
  x86_64_image="${image}:${branch}-x86_64"

  podman pull --arch arm64 "$aarch64_image"
  podman pull --arch amd64 "$x86_64_image"

  if [[ "$branch" == main ]]; then
    target="$manifest"
  else
    target="${image}:${branch}"
  fi
  podman manifest rm "$target" 2>/dev/null || true
  podman manifest create "$target" "$aarch64_image" "$x86_64_image"
  podman manifest push --all "$target" "docker://$target"
done < "$(dirname "$0")/images.txt"
