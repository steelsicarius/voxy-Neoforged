# Voxy (NeoForge port)

Port of Voxy — a far-distance (LoD) rendering mod for Minecraft — from Fabric
(MC 26.1.x, origin tree `voxy-2612/`, read-only) to **NeoForge 26.1.2**.

- Build: `JAVA_HOME=/usr/java/jdk-25-oracle-aarch64 ./gradlew build`
- Run client: `JAVA_HOME=/usr/java/jdk-25-oracle-aarch64 ./gradlew runClient`
  (dev runs use the local run/ directory).
- The dev server for tests must use port 25599 (25565 is already in use on this host).

Upstream (Fabric): https://github.com/mircro-cortex/voxy by Cortex.
License: All Rights Reserved (see `voxy-2612/LICENSE.md`).
