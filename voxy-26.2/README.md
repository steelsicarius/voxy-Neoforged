# Voxy (NeoForge port)

Port of Voxy — a far-distance (LoD) rendering mod for Minecraft — from Fabric
(MC 26.1.x, origin tree `voxy-2612/`, read-only) to **NeoForge 26.2**.

- MC 26.2 / NeoForge 26.2.0.90 / Sodium mc26.2-0.9.2-neoforge (mismo plugin moddev 2.0.148 que 26.1.2).
- Build: `JAVA_HOME=/usr/java/jdk-25-oracle-aarch64 ./gradlew build`
- Run client: `JAVA_HOME=/usr/java/jdk-25-oracle-aarch64 ./gradlew runClient`
  (dev runs use the local run/ directory).
- The dev server for tests must use port 25599 (25565 is already in use on this host).

## Estado del port

- Compila y empaqueta (`build/libs/voxy-<versión>.jar`).
- Librerías embebidas vía JarJar de NeoForge (`META-INF/jarjar/`): jedis, org.json
  (transitiva de jedis), rocksdbjni, commons-pool2, xz y los bindings+natives LWJGL de
  lmdb/zstd. `lz4` no se embebe: MC 26.1.2 trae el fork `at.yawk.lz4` (paquetes
  `net.jpountz`) en el classpath de lanzamiento. `sqlite-jdbc` queda como runtime de dev,
  igual que el build de Fabric.
- Los natives de LWJGL se publican en `natives-repo/` con coordenadas únicas y las tres
  plataformas fusionadas en un jar por módulo (ver `natives-repo/README.md`): FML deduplica
  librerías anidadas por identidad de manifest y los natives oficiales comparten manifest
  entre plataformas.
- Validado en producción: servidor NeoForge 26.1.2 con el jar en `mods/` carga el mod, las
  9 librerías anidadas y llega a `Done` (puerto 25599). LMDB/ZSTD cargan su `.so` real via
  el classloader de las clases binding (probado con JNI en aarch64).
- Menú de configuración vía API de Sodium (`@ConfigEntryPointForge`), equivalente al
  entrypoint de Fabric `sodium:config_api_user`; pantalla registrada además como
  `IConfigScreenFactory`.
- Huecos/cuadros blancos al alejarse: corregidos con tres mecanismos en
  `IngestRetryQueue` + `VoxyClient`:
  1. Reintento del ingest cuando la luz del cliente aún no está disponible (la luz viaja
     en una cola que se procesa a ritmo limitado por tick; antes el fallo era silencioso
     y el chunk nunca entraba en el mundo voxy).
  2. Captura incondicional del estado final del chunk en `ChunkEvent.Unload` (antes solo
     con Bobby instalado).
  3. Barrido de conciliación cada 3 s de todos los chunks cargados alrededor del jugador
     (radio = render distance de vanilla), para que todo alrededor quede generado y
     guardado aunque los eventos se pierdan.
- Pendiente: prueba visual del cliente (`runClient` necesita display; este host no tiene
  Xvfb y sin sudo no se puede instalar).

Upstream (Fabric): https://github.com/mircro-cortex/voxy by Cortex.
License: All Rights Reserved (see `voxy-2612/LICENSE.md`).

## Actualización a voxy 0.2.20-beta (upstream `voxy-dev`, Fabric/MC 26.3)

Portados los cambios de upstream entre 0.2.18-beta y 0.2.20-beta, adaptados a
MC 26.2 / NeoForge:

- **Migración de mundos por DataVersion** (`Mapper`/`SaveLoadSystem3`): los blockstates
  guardados llevan `version` (data version) y se migran con `DataFixers`; nuevo
  `SaveLoadSystem3.remap` para remapear block/biome ids de secciones guardadas.
- **Importador de mundos**: chunks NBT actualizados con `DataFixers` (`References.CHUNK`).
- **Config de niebla**: `useEnvironmentalFog` → `fogMode` (`FogMode`:
  OFF/FOG/FADE/FOG_AND_FADE), fade en shader (`HAS_FADE`/`fadeParams`), textos nuevos.
- **Far/near plane dinámicos**: `setProjection(projection, near, far)`,
  `useDynamicFarPlane` y uniforms `vxFarPlane`/`vxNearPlane`.
- **`MixinLevelExtractor` nuevo** (fix real del port 26.2: en MC 26.2 `setLevel`/`allChanged`
  se movieron de `LevelRenderer` a `LevelExtractor`, así que los injects antiguos nunca
  disparaban); eliminados esos injects de `MixinLevelRenderer`.
- **`MixinMinecraft`**: `WrapOperation` sobre `GpuBackend.createDevice` (firma de 26.2:
  `(J,ShaderSource,GpuDebugOptions,Runnable)`) que rechaza `VulkanBackend` — voxy solo
  soporta OpenGL.
- **MixinConfig** (plugin, adaptado a `ModList`) en ambos mixin configs.
- **Iris**: `DepthTransformerAccessor` + patcher revZ de shaderpacks (glsl-transformer,
  compileOnly; iris 1.11.4 lo trae embebido), `USED_IRIS_VIEWPORT`,
  `conditionallyReverseRevZ` en `VoxyUniforms`.
- **Ciclo de vida** de `visbleSectionStream` + checks de null en sodium mixins.
- **mods.toml**: nuevo break con iris >=1.12 (equivalente del `breaks` de fabric.mod.json).

Pendiente igual que antes: prueba visual del cliente.

## Tamaño del jar: natives de RocksDB recortados (2026-10-23)

El jar distribuible baja de 75,5 MB a ~12,4 MB (el jar de Fabric pesa 12 MB). El 96 % del
peso era `rocksdbjni-10.2.1.jar` (72,8 MB), que trae natives precompilados para una docena
de plataformas (osx, linux32, musl, s390x, riscv64, ppc64le, aarch64, ...).

- Por defecto se embebe un `rocksdbjni` recortado con el mismo filtro que usa el build de
  Fabric (`makeExcludedRocksDB`): solo los natives win64 y linux-x64 (~9 MB). Las clases
  Java y el manifest van intactos, así que el backend RocksDB (el storage por defecto de
  voxy) funciona exactamente igual en Windows y Linux x64.
- `./gradlew build -PincludeOtherArchs=true` embebe el jar upstream completo con los
  natives de todas las plataformas (equivalente al `INCLUDE_OTHER_ARCHS` de Fabric):
  usarlo para clientes/servidores ARM64 u otros exóticos que necesiten el backend RocksDB.
- El jar recortado se publica en `natives-repo/me/cortex/vojynatives/rocksdbjni/`
  (regenerable con `./gradlew stripRocksdbNatives`): jarJar resuelve su configuración al
  construir el grafo de tareas, antes de que cualquier tarea pueda generarlo, así que el
  fichero debe existir en un checkout limpio.
- Validado en el servidor de pruebas (NeoForge 26.1.2, aarch64, puerto 25598): FML carga
  el jar de 12,4 MB y las 9 librerías anidadas, el backend RocksDB se registra y el
  servidor llega a `Done`. Sin coste de rendimiento: es solo contenido del paquete, el
  código es idéntico.

## Carga de LODs del servidor (voxy-server/lss) en multijugador — mismo estado que 26.1.2

La carga de los chunks de voxy que envía el servidor (mod `lss` /
voxy-server-side) usa exactamente el mismo código que el port 26.1.2 validado
en multijugador (2026-10-23): los LODs aparecen por cercanía al jugador,
avanzando progresivamente, sin cargas aleatorias ni bloqueos.

- El bridge de ingesta cruda del mod lss (`VoxelIngestService.rawIngest`)
  entra por la cola FIFO del ingest service, preservando el orden de llegada
  (cercanía) hasta la cola de construcción de mallas de
  `RenderGenerationService` (prioriza por nivel LOD y, dentro del mismo
  nivel, por orden de inserción).
- `IngestRetryQueue` contiene la compatibilidad con lss: mientras el
  servidor lss transmite terreno lejano (`lssServerActive()`), se suprime el
  fallback local "sin luz" para no sobrescribir con datos oscuros las
  columnas (correctamente iluminadas) que envía el servidor; el barrido de
  conciliación y los reintentos solo afectan a los chunks de vanilla cargados
  alrededor del jugador.
- Verificado a nivel de bytecode: las clases compiladas del mecanismo de
  carga (`IngestRetryQueue`, `VoxelIngestService`, `RenderGenerationService`,
  `AsyncNodeManager`, `VoxyClient`) son byte-idénticas entre los tres ports
  (mismo MD5 en los jars de 26.1.2, 26.2 y 26.3; comprobación 2026-10-23).

Jar a instalar: `build/libs/voxy-0.2.20-beta.jar` (único artefacto de
`./gradlew build`; cualquier jar previo con otro nombre en `build/libs` es un
resto de una build 0.2.18 antigua y no debe usarse).
