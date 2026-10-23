# VoxyNatives — LWJGL natives repackaged for JarJar

Two adaptations were needed to embed the LWJGL natives jars through NeoForge's JarJar:

1. **Unique coordinates.** The official natives jars (`org.lwjgl:lwjgl-<module>:<version>:natives-<platform>`)
   share their `group:artifact` with the main bindings jars. Both ModDevGradle's JarJar
   metadata writer (fails the build on a duplicate identifier) and FML's JarSelector (keeps
   only a single jar per `group:artifact` identifier) cannot handle that, so the jars are
   published here under unique coordinates: `me.cortex.vojynatives:<artifact>:<version>`.

2. **One jar per module.** FML derives the identity of a nested library from the jar
   manifest (`Implementation-Title`), and the official LWJGL natives jars of one module
   carry the *same* manifest across platforms (`lwjgl-lmdb`, `build 2`, ...). Embedding the
   platform jars separately therefore makes FML consider them duplicates ("Attempted to
   select two dependency jars ... same identification") and keep only one platform. The
   platform jars (linux x64, linux arm64, windows x64 - macOS is not bundled, mirroring the
   upstream Fabric build) are merged into a single jar per module; the per-platform
   resource paths (`linux/x64/...`, `linux/arm64/...`, `windows/x64/...`) do not overlap,
   and LWJGL picks the entry matching the running platform at extraction time.
   `META-INF/INDEX.LIST` is dropped so classpath resource lookups scan the whole jar.

## Contents (LWJGL 3.4.1, matching the version shipped with MC 26.2)

- `lwjgl-lmdb-natives`  - liblwjgl_lmdb.so/.dll for linux x64, linux arm64, windows x64
- `lwjgl-zstd-natives`  - liblwjgl_zstd.so/.dll for linux x64, linux arm64, windows x64
- `rocksdbjni`           - upstream `org.rocksdb:rocksdbjni:10.2.1` repackaged without the
  exotic-platform natives: only `librocksdbjni-linux64.so` and `librocksdbjni-win64.dll`
  are kept (~9 MB instead of 72.8 MB), the exact same filter as upstream Fabric's
  `makeExcludedRocksDB`. Classes and manifest are untouched, so the RocksDB backend
  (voxy's default storage) behaves identically on win64/linux-x64. jarJar embeds this jar
  by default because it resolves its configuration while building the task graph, before
  any task could produce the file: the jar must exist on a fresh checkout, so it is
  committed here. `./gradlew build -PincludeOtherArchs=true` embeds the full upstream jar
  with every platform's natives instead (aarch64, musl, osx, ...).

## Regenerating

When the LWJGL version changes, rebuild these jars by merging the new natives jars the
same way (union of entries, single manifest, no `META-INF/INDEX.LIST`) and update the
`jarJar` dependency versions in `build.gradle`.

When the rocksdb version changes, run `./gradlew stripRocksdbNatives` (it repackages the
upstream jar with the win64/linux-x64-only filter into
`me/cortex/vojynatives/rocksdbjni/<version>/`) and commit the regenerated jar.
