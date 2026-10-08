#!/bin/bash
set -euo pipefail

image=${1:?Supply the container image name}
podman run --rm --entrypoint /bin/sh "$image" -c '
  test "$(id -u)" = 10001 &&
  test -w /home/wsr/tmp &&
  test -w /home/wsr/.m2 &&
  test -r /opt/wsr/wanaku-semantic-router.jar &&
  for library in /opt/wsr/lib/*.jar; do test -r "$library" || exit 1; done
'
# OpenShift assigns an arbitrary UID with membership in the root group.
podman run --rm --user 12345:0 --entrypoint /bin/sh "$image" -c '
  test "$(id -u)" = 12345 &&
  test "$(id -g)" = 0 &&
  mkdir -p /home/wsr/tmp/smoke/catalog /home/wsr/tmp/smoke/repository &&
  touch /home/wsr/tmp/smoke/catalog/main.yaml \
    /home/wsr/tmp/smoke/repository/artifact.jar /home/wsr/.m2/smoke &&
  test -r /opt/wsr/wanaku-semantic-router.jar &&
  for library in /opt/wsr/lib/*.jar; do test -r "$library" || exit 1; done
'
# Check Java home and SDK helper initialization with and without a generated passwd entry.
for passwd_mode in true false; do
  podman run --rm --user 12345:0 --passwd="$passwd_mode" --entrypoint /bin/sh "$image" -c '
  set -eu
  test "$HOME" = /home/wsr
  command -v getent > /dev/null
  if test "$1" = true; then
    getent passwd 12345 > /dev/null
  elif getent passwd 12345 > /dev/null; then
    echo "Unexpected passwd entry with --passwd=false" >&2
    exit 1
  fi
  cat > /home/wsr/tmp/HomeSmoke.java << "JAVA"
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;

class HomeSmoke {
    public static void main(String[] args) throws Exception {
        if (!"/home/wsr".equals(System.getProperty("user.home"))) {
            throw new IllegalStateException("Java home does not match the container home");
        }
        Path source = Path.of("/home/wsr/tmp/init-source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("smoke.txt"), "SDK initialization fixture");
        try (var git = Git.init().setDirectory(source.toFile()).call()) {
            git.add().addFilepattern("smoke.txt").call();
            git.commit().setMessage("Container smoke fixture")
                .setAuthor("WSR smoke", "smoke@example.invalid")
                .setCommitter("WSR smoke", "smoke@example.invalid").call();
        }
    }
}
JAVA
  java -Djava.io.tmpdir=/home/wsr/tmp --class-path "/opt/wsr/lib/*" /home/wsr/tmp/HomeSmoke.java
  if java -Djava.io.tmpdir=/home/wsr/tmp -jar /opt/wsr/wanaku-semantic-router.jar \
    runtime --init-from file:///home/wsr/tmp/init-source --retries 0 --init-timeout-seconds 15 \
    > /home/wsr/tmp/init-smoke.log 2>&1; then
    cat /home/wsr/tmp/init-smoke.log
    exit 1
  else
    status=$?
  fi
  if test "$status" != 2 || ! test -f /home/wsr/.wanaku/wsr/cloned-repo/smoke.txt; then
    cat /home/wsr/tmp/init-smoke.log
    exit 1
  fi
' wsr-home-smoke "$passwd_mode"
done
podman run --rm --user 12345:0 "$image" runtime --help
podman run --rm "$image" --help
podman run --rm "$image" --version
podman run --rm "$image" runtime --help
podman run --rm "$image" preview --help
probe_directory=$(cd -- "$(dirname -- "$0")" && pwd)
podman run --rm --user 12345:0 --entrypoint java \
  --volume "$probe_directory/DependencySmoke.java:/home/wsr/tmp/DependencySmoke.java:ro" \
  "$image" -Djava.io.tmpdir=/home/wsr/tmp --class-path '/opt/wsr/lib/*' /home/wsr/tmp/DependencySmoke.java \
  /home/wsr/tmp/smoke-repository
