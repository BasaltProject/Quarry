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
  <img alt="Java Edition" src="https://img.shields.io/badge/Java_Edition-26.3-62B47A?logo=minecraft&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white">
  <img alt="JDK" src="https://img.shields.io/badge/JDK-25-F89820?logo=openjdk&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-green">
</p>

## About

Much of what Basalt Server needs is a fact the game keeps in code: which blocks exist and what shape they have, how
hard each one is, which entities there are. Quarry reads those facts out of the official server jar, so a new Minecraft
version is a rerun and not an edit.

It is written in Kotlin because extracting from the jar means running Mojang's own code, which already needs a JVM.
It is a separate repository and nothing from it is part of the Server build: what leaves Quarry are data tables, which
Server commits as C++. Nothing from Mojang is redistributed; the jar is one you downloaded yourself and only the
numbers read from it leave.

## Usage

Needs JDK 25. Gradle comes with the repository.

```bash
./gradlew extract -Pquarry.jar=<server.jar>
./gradlew generate -Pquarry.jar=<server.jar> -Pquarry.version=<game version> -Pquarry.out=<Server checkout>
```

`<server.jar>` is the bundler jar Mojang distributes. `extract` dumps the registries and data into `build/quarry/`, and
`generate` turns them into the C++ tables under `<out>/server-registry/`.

## License

[GPL-3.0-only](LICENSE).
