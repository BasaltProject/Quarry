<p align="center">
  <a href="https://raw.githubusercontent.com/BasaltProject/.github/main/assets/basalt-logo-white.svg#gh-dark-mode-only">
    <img src="https://raw.githubusercontent.com/BasaltProject/.github/main/assets/basalt-logo-white.svg" alt="Basalt" width="360">
  </a>
  <a href="https://raw.githubusercontent.com/BasaltProject/.github/main/assets/basalt-logo-black.svg#gh-light-mode-only">
    <img src="https://raw.githubusercontent.com/BasaltProject/.github/main/assets/basalt-logo-black.svg" alt="Basalt" width="360">
  </a>
</p>

<h2 align="center">Basalt Quarry</h2>

<p align="center">
  <strong>Extracts the data Basalt needs from the official Minecraft server jar.</strong>
</p>

<p align="center">
  <img alt="Minecraft" src="https://img.shields.io/badge/Minecraft-26.3-62B47A?logo=minecraft&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white">
  <img alt="JDK" src="https://img.shields.io/badge/JDK-25-F89820?logo=openjdk&logoColor=white">
  <img alt="Build" src="https://img.shields.io/badge/build-Gradle-02303A?logo=gradle&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-green">
</p>

## Overview

Quarry is part of the Basalt Project. Basalt Server reimplements the game, and a lot of what it needs is a fact the
game keeps in code: which blocks exist and what shape they have, how hard each one is, which entities there are and
how big. Quarry reads those facts out of the official server jar, so a new Minecraft version is a rerun and not an
edit.

It is a Kotlin tool because extracting from the jar means running Mojang's own code: bootstrapping its registries and
asking it questions. That already needs a JVM, so Quarry lives in one. It is a separate repository and nothing from it
is part of the Basalt Server build; what leaves Quarry are data tables, which Server commits as C++.

It never redistributes anything from Mojang. The jar is one the developer downloaded themselves, and only the numbers
read from it leave.

## Building

Requires JDK 25. Gradle comes with the repository, so there is nothing to install.

## Usage

```bash
./gradlew extract -Pquarry.jar=<server.jar>
```

`<server.jar>` is the bundler jar Mojang distributes (the one with `META-INF/versions/` inside), not the unpacked
server jar. `extract` works in `build/quarry/` and does four things:

1. Unpacks the bundler: the real server jar and its libraries under `vanilla/`.
2. Runs the jar's own report generator (`--reports`) into `generated/`: the registries, the block states, the
   packets and the item components.
3. Compiles the harnesses in `src/dump/kotlin/` against that jar. They call the game's own classes, so they are built
   against the version being extracted, and a version that changes one of those classes breaks the build there.
4. Runs them in one JVM, bootstrapping the real registries and binding the vanilla tags, and writes what vanilla
   keeps in code as tab-separated files under `dumps/`.

`build/install/quarry/bin/quarry` (from `./gradlew installDist`) has three commands: `version <server.jar>` prints
the game version a bundler carries, `unpack <server.jar> <dir>` does step 1 alone and `generate` is the last step of
the task of the same name.

| Dump | Holds |
|---|---|
| `blocks.tsv` | Hardness, movement and note block instruments |
| `shapes.tsv` | Collision and outline shapes |
| `support.tsv` | Support rules, random ticks, conductors, piston reactions, fire odds, fuel |
| `items.tsv` | What the reports do not carry about items |
| `block_entities.tsv`, `entities.tsv` | Block entity and entity types, shapes and attributes |
| `world.tsv`, `map_colors.tsv`, `block_sounds.tsv` | Points of interest, biome order, map colours, block sounds |

A failing step stops the run, and Gradle names the task that failed.

## Generating the tables

```bash
./gradlew generate -Pquarry.jar=<server.jar> -Pquarry.version=<game version> \
    -Pquarry.out=<Server checkout> -Pquarry.style=<Server checkout>/.clang-format
```

`generate` runs `extract` and then fills the templates in `src/main/resources/templates/` with tables built from
the reports and the dumps (`src/main/kotlin/.../tables/`), formats them with `clang-format` and writes them under
`<out>/server-registry/`. Every number in those files is a fact read from the jar, so a new Minecraft version is a
rerun, not an edit. `<out>` can be any directory, which is handy for diffing two versions.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
