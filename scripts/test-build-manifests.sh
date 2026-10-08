#!/bin/bash
set -euo pipefail

repository=$(cd "$(dirname "$0")/.." && pwd)
test_directory=$(mktemp -d)
trap 'rm -rf "$test_directory"' EXIT
export WSR_MANIFEST_TEST_LOG="$test_directory/podman.log"
cat > "$test_directory/podman" <<'PODMAN'
#!/bin/bash
printf '%s\n' "$*" >> "$WSR_MANIFEST_TEST_LOG"
if [[ "${WSR_MANIFEST_TEST_FAIL_CREATE:-}" == 1 && "$1 $2" == 'manifest create' ]]; then
  exit 1
fi
PODMAN
chmod +x "$test_directory/podman"
export PATH="$test_directory:$PATH"

"$repository/build-manifests.sh" main
cat > "$test_directory/expected" <<'EXPECTED'
pull --arch arm64 quay.io/wanaku/wanaku-semantic-router:main-aarch64
pull --arch amd64 quay.io/wanaku/wanaku-semantic-router:main-x86_64
manifest rm quay.io/wanaku/wanaku-semantic-router:latest
manifest create quay.io/wanaku/wanaku-semantic-router:latest quay.io/wanaku/wanaku-semantic-router:main-aarch64 quay.io/wanaku/wanaku-semantic-router:main-x86_64
manifest push --all quay.io/wanaku/wanaku-semantic-router:latest docker://quay.io/wanaku/wanaku-semantic-router:latest
EXPECTED
diff -u "$test_directory/expected" "$WSR_MANIFEST_TEST_LOG"

: > "$WSR_MANIFEST_TEST_LOG"
"$repository/build-manifests.sh" v0.1.0
sed 's/main-/v0.1.0-/g; s/:latest/:v0.1.0/g' "$test_directory/expected" > "$test_directory/version-expected"
diff -u "$test_directory/version-expected" "$WSR_MANIFEST_TEST_LOG"

: > "$WSR_MANIFEST_TEST_LOG"
printf -v long_tag '%121s' ''
long_tag=${long_tag// /a}
for invalid_tag in 'invalid/tag' "$long_tag"; do
  if "$repository/build-manifests.sh" "$invalid_tag" 2>/dev/null; then
    echo "Invalid image tag was accepted" >&2
    exit 1
  fi
done
test ! -s "$WSR_MANIFEST_TEST_LOG"

if WSR_MANIFEST_TEST_FAIL_CREATE=1 "$repository/build-manifests.sh" main; then
  echo "Manifest creation failure was ignored" >&2
  exit 1
fi
if grep -q '^manifest push' "$WSR_MANIFEST_TEST_LOG"; then
  echo "Manifest was pushed after creation failed" >&2
  exit 1
fi
echo "Container manifest checks passed"
