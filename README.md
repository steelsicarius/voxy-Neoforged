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
