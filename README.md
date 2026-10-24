# Voxy NeoForge — monorepo multiversion

Port de [Voxy](https://github.com/mircro-cortex/voxy) (mod de render LoD de Fabric,
de Cortex) a **NeoForge**, en un único multiproyecto Gradle con **tres subproyectos**,
uno por versión de Minecraft:

| Subproyecto  | MC      | NeoForge       | Sodium          |
|--------------|---------|----------------|-----------------|
| `voxy-26.1.2` | 26.1.2  | 26.1.2.114     | 0.9.1-neoforge  |
| `voxy-26.2`   | 26.2    | 26.2.0.90      | 0.9.2-neoforge  |
| `voxy-26.3`   | 26.3    | 26.3.0.69-beta | 0.9.2-neoforge  |

Cada subproyecto es el proyecto ModDevGradle completo de su versión (mismas fuentes,
`natives-repo/` y `gradle.properties` que las ramas `neoforge-26.X` del repositorio),
con tres adaptaciones mínimas para vivir dentro de un multiproyecto Gradle:

1. `plugins { }` sin versión para `net.neoforged.moddev` (en un multiproyecto la
   versión solo puede fijarse en la raíz; las tres versiones usan la misma 2.0.148).
2. Carga explícita de su propio `gradle.properties` (Gradle solo auto-carga el de la
   raíz).
3. Sin `settings.gradle` ni wrapper anidados: el wrapper y el `settings.gradle` raíz
   son el único punto de entrada.


## Sincronización con las ramas standalone

Las ramas `neoforge-26.1.2` / `neoforge-26.2` / `neoforge-26.3` del repositorio
siguen siendo los proyectos standalone (una carpeta por versión, buildable por su
cuenta). El monorepo es la **fuente de verdad** para editar; `sync-branches.sh` mantiene
las ramas al día:

```sh
./sync-branches.sh push -m "Descripcion del cambio"   # monorepo -> ramas (rsync + commit por version)
./sync-branches.sh pull                                # ramas -> monorepo (para importar un cambio hecho fuera)
```

El script (`sync/adapt_build.py` con anclas byte-exactas, ida y vuelta verificada)
convierte entre las dos formas: bloque plugins con versión + tarea wrapper (standalone)
frente a bloque plugins sin versión + cargador de properties (monorepo), y la cabecera
`org.gradle.*` de `gradle.properties` (solo standalone). `push` usa `rsync --delete`
excluyendo `.git`, `.gradle`, `build`, `run`, `test-server`, `settings.gradle`,
`gradlew`, `gradlew.bat` y `gradle/` (wrapper): cualquier otro fichero no versionado
de las carpetas standalone se perdería al sincronizar.

## Notas del entorno

- Java 25 en `/usr/java/jdk-25-oracle-aarch64`; wrapper Gradle 9.2.1.
- El plugin ModDevGradle y la versión del wrapper deben coincidir entre las tres
  versiones; al actualizar el MDK, cambiar la versión en el `build.gradle` raíz (y
  en los tres subproyectos si se reconstruyen las ramas standalone).
- Servidor de pruebas: `voxy-26.1.2/test-server/` (untracked, vive en la carpeta
  standalone de 26.1.2); usar el puerto 25598 (el 25565 está ocupado y el 25599 lo
  usa otro proyecto).

Upstream (Fabric): https://github.com/mircro-cortex/voxy by Cortex.
License: All Rights Reserved (ver `voxy-26.1.2/LICENSE.md`).
