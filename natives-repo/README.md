# VoxyNatives — LWJGL natives repackaged for JarJar

The official LWJGL natives jars (`org.lwjgl:lwjgl-<module>:<version>:natives-<platform>`)
share their `group:artifact` with the main bindings jars. Both ModDevGradle's JarJar
metadata writer (fails the build on a duplicate identifier) and FML's JarSelector (keeps
only a single jar per `group:artifact` identifier) cannot handle that, so these jars are
published here under unique coordinates:

    me.cortex.vojynatives:lwjgl-<module>-natives-<platform>:<lwjgl version>

The jar contents are untouched - LWJGL locates the native inside the jar by resource path
and extracts it at runtime, so only the outer file name/coordinates change.

## Contents (LWJGL 3.4.1, matching the version shipped with MC 26.1.2)

- `lwjgl-lmdb-natives-linux`, `lwjgl-lmdb-natives-linux-arm64`, `lwjgl-lmdb-natives-windows`
- `lwjgl-zstd-natives-linux`, `lwjgl-zstd-natives-linux-arm64`, `lwjgl-zstd-natives-windows`

(macOS is not bundled, mirroring the upstream Fabric build.)

## Regenerating

When the LWJGL version changes, re-create this directory from the Gradle cache:

    for f in ~/.gradle/caches/modules-2/files-2.1/org.lwjgl/{lwjgl-lmdb,lwjgl-zstd}/<new>/~/*-natives-*.jar; do
      b=$(basename "$f")
      art=$(echo "$b" | sed -E 's/^(.+?)-([0-9][0-9.]*)-(natives-[a-z0-9-]+)\.jar$/\1-\3/')
      ver=$(echo "$b" | sed -E 's/^(.+?)-([0-9][0-9.]*)-(natives-[a-z0-9-]+)\.jar$/\2/')
      mkdir -p "natives-repo/me/cortex/vojynatives/$art/$ver"
      cp "$f" "natives-repo/me/cortex/vojynatives/$art/$ver/$art-$ver.jar"
    done

and update the `jarJar` dependency versions in `build.gradle`.
