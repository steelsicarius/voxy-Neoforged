#!/usr/bin/env python3
"""Convierte build.gradle/gradle.properties de un subproyecto del monorepo
(voxy-multiversion/voxy-26.X/) al formato standalone (rama neoforge-26.X) y
viceversa. Las transformaciones son con anclas byte-exactas: si un ancla deja
de encontrarse, aborta en lugar de corromper el fichero. Ambas direcciones son
idempotentes.

Diferencias standalone -> monorepo (y viceversa):
  * build.gradle: el bloque plugins+tarea-wrapper (con version del plugin
    moddev) se sustituye por el bloque plugins sin version + cargador del
    gradle.properties propio del subproyecto.
  * gradle.properties: la cabecera org.gradle.* (con su comentario) solo
    existe en standalone; en el monorepo vive en el gradle.properties raiz.
  * Los ficheros settings.gradle, gradlew, gradlew.bat y gradle/ (wrapper)
    solo existen en standalone: sync-branches.sh los excluye del rsync.

Uso:
  adapt_build.py --to-monorepo   <dir>
  adapt_build.py --to-standalone <dir>
"""

import argparse
import sys

MODDEV = "2.0.148"

# NOTA: ambos bloques terminan en '}' SIN newline final, para que el reemplazo
# no toque los blancos que haya despues del bloque.
PLUGINS_STANDALONE = """plugins {
    id 'java-library'
    id 'maven-publish'
    id 'net.neoforged.moddev' version '%s'
    id 'idea'
}

tasks.named('wrapper', Wrapper).configure {
    // Define wrapper values here so as to not have to always do so when updating gradlew.properties.
    // Switching this to Wrapper.DistributionType.ALL will download the full gradle sources that comes with
    // documentation attached on cursor hover of gradle classes and methods. However, this comes with increased
    // file size for Gradle. If you do switch this to ALL, run the Gradle wrapper task twice afterwards.
    // (Verify by checking gradle/wrapper/gradle-wrapper.properties to see if distributionUrl now points to `-all`)
    distributionType = Wrapper.DistributionType.BIN
}""" % MODDEV

PLUGINS_MONOREPO = """plugins {
    // Multiproject: la version del plugin moddev se fija una sola vez en el
    // build.gradle raiz; los subproyectos lo aplican sin version.
    id 'java-library'
    id 'maven-publish'
    id 'net.neoforged.moddev'
    id 'idea'
}

// En un multiproyecto Gradle solo auto-carga el gradle.properties del proyecto raiz:
// cargar aqui el de este subproyecto explicitamente (las propiedades org.gradle.*
// viven solo en el gradle.properties raiz).
def subprojectProperties = new Properties()
subprojectProperties.load(file('gradle.properties').newInputStream())
subprojectProperties.each { key, value ->
    if (!project.hasProperty(key)) {
        project.ext.set(key, value)
    }
}"""

# Cabecera exacta del gradle.properties standalone (7 lineas: comentario,
# 5 propiedades org.gradle.* y una linea en blanco).
GRADLE_PROPS_HEADER = (
    "# Sets default memory used for gradle commands. Can be overridden by user or command line properties.\n"
    "org.gradle.jvmargs=-Xmx2G\n"
    "org.gradle.daemon=true\n"
    "org.gradle.parallel=true\n"
    "org.gradle.caching=true\n"
    "org.gradle.configuration-cache=true\n"
    "\n"
)


def fail(msg):
    sys.exit(f"adapt_build.py: ERROR: {msg}")


def canonical_properties(d):
    """Devuelve el contenido canonico (monorepo) de gradle.properties."""
    p = open(d + "/gradle.properties").read()
    if p.startswith(GRADLE_PROPS_HEADER):
        return p[len(GRADLE_PROPS_HEADER):]
    lines = p.split("\n")
    if lines and lines[0].startswith("# Sets default memory used for gradle commands"):
        lines = lines[1:]
    lines = [l for l in lines if not l.startswith("org.gradle.")]
    while lines and lines[0] == "":
        lines = lines[1:]
    return "\n".join(lines)


def to_monorepo(d):
    bg = d + "/build.gradle"
    s = open(bg).read()
    if PLUGINS_MONOREPO in s:
        pass  # ya convertido (idempotente)
    elif PLUGINS_STANDALONE in s:
        s = s.replace(PLUGINS_STANDALONE, PLUGINS_MONOREPO, 1)
        open(bg, "w").write(s)
    else:
        fail(f"ancla del bloque plugins standalone no encontrada en {bg}")
    content = canonical_properties(d)  # leer ANTES de truncar con open(..., "w")
    open(d + "/gradle.properties", "w").write(content)


def to_standalone(d):
    gp = d + "/gradle.properties"
    p = open(gp).read()
    if p.startswith(GRADLE_PROPS_HEADER):
        pass  # ya convertido (idempotente)
    else:
        content = GRADLE_PROPS_HEADER + canonical_properties(d)  # leer antes de truncar
        open(gp, "w").write(content)

    bg = d + "/build.gradle"
    s = open(bg).read()
    if PLUGINS_STANDALONE in s:
        pass
    elif PLUGINS_MONOREPO in s:
        s = s.replace(PLUGINS_MONOREPO, PLUGINS_STANDALONE, 1)
        open(bg, "w").write(s)
    else:
        fail(f"ancla del bloque plugins monorepo no encontrada en {bg}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--to-monorepo", metavar="DIR")
    g.add_argument("--to-standalone", metavar="DIR")
    a = ap.parse_args()
    if a.to_monorepo:
        to_monorepo(a.to_monorepo)
    else:
        to_standalone(a.to_standalone)
