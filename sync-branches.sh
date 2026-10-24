#!/usr/bin/env bash
# Sincroniza los subproyectos del monorepo (voxy-multiversion/, rama `multiversion`)
# con las carpetas/ramas standalone de cada version (voxy-26.1.2 -> neoforge-26.1.2,
# voxy-26.2 -> neoforge-26.2, voxy-26.3 -> neoforge-26.3).
#
#   ./sync-branches.sh push [-m "mensaje de commit"]   # monorepo -> ramas (y commit)
#   ./sync-branches.sh pull                             # ramas -> monorepo (y commit)
#
# "push" es el flujo normal: el monorepo es la fuente de verdad; el script copia el
# subproyecto a la carpeta standalone, reinserta los pedazos standalone (version del
# plugin, tarea wrapper, cabecera org.gradle.*, settings.gradle + wrapper) y hace
# commit en la rama correspondiente. "pull" hace lo contrario para importar un cambio
# hecho directamente en una carpeta standalone.
#
# NOTA: push usa rsync --delete; los ficheros no versionados de las carpetas destino
# que no esten excluidos se perderian. Estan excluidos: .git, .gradle, build, run,
# test-server, settings.gradle, gradlew, gradlew.bat, gradle/ (wrapper).

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
VOXY="$(dirname "$ROOT")"
MODE="${1:-}"; shift || true
MSG="Sync desde/para monorepo multiversion"

if [ "$MODE" = "push" ]; then
    if [ "${1:-}" = "-m" ]; then MSG="$2"; shift 2; fi
elif [ "$MODE" = "pull" ]; then
    :
else
    echo "uso: $0 push [-m \"mensaje\"] | pull" >&2; exit 1
fi

EXCLUDES=(--exclude='.git' --exclude='.gradle' --exclude='build' --exclude='run'
          --exclude='test-server' --exclude='settings.gradle'
          --exclude='gradlew' --exclude='gradlew.bat' --exclude='gradle/')

for V in 26.1.2 26.2 26.3; do
    SUB="$ROOT/voxy-$V"
    STANDALONE="$VOXY/voxy-$V"
    [ -d "$STANDALONE/.git" ] || { echo "no existe la carpeta standalone voxy-$V"; exit 1; }

    if [ "$MODE" = "push" ]; then
        rsync -a --delete --no-owner --no-group --omit-dir-times "${EXCLUDES[@]}" "$SUB/" "$STANDALONE/"
        python3 "$ROOT/sync/adapt_build.py" --to-standalone "$STANDALONE"
        (cd "$STANDALONE" && git add -A -- ':(exclude)test-server' && \
            if git diff --cached --quiet; then echo "voxy-$V: sin cambios"; \
            else git commit -q -m "$MSG" && echo "voxy-$V: commit $(git rev-parse --short HEAD)"; fi)
    else
        rsync -a --delete --no-owner --no-group --omit-dir-times "${EXCLUDES[@]}" "$STANDALONE/" "$SUB/"
        python3 "$ROOT/sync/adapt_build.py" --to-monorepo "$SUB"
        (cd "$ROOT" && git add "voxy-$V" && \
            if git diff --cached --quiet; then echo "voxy-$V: sin cambios"; \
            else git commit -q -m "Import desde voxy-$V: $MSG" && echo "voxy-$V: importado"; fi)
    fi
done

if [ "$MODE" = "pull" ]; then
    (cd "$ROOT" && if git diff --cached --quiet; then echo "monorepo: sin cambios"; \
     else echo "monorepo: cambios listos para commit (revisa con git diff --cached)"; fi)
fi
echo "Listo."
