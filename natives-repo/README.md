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

## Contents (LWJGL 3.4.1, matching the version shipped with MC 26.1.2)

- `lwjgl-lmdb-natives`  - liblwjgl_lmdb.so/.dll for linux x64, linux arm64, windows x64
- `lwjgl-zstd-natives`  - liblwjgl_zstd.so/.dll for linux x64, linux arm64, windows x64

## Regenerating

When the LWJGL version changes, rebuild these jars by merging the new natives jars the
same way (union of entries, single manifest, no `META-INF/INDEX.LIST`) and update the
`jarJar` dependency versions in `build.gradle`.
