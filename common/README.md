# Shared module

`common` independently compiles the shared subset of `../src/main/java` against
Minecraft 1.20.1 official Mojang mappings. It has no Fabric Loader, Fabric API,
Forge or Tom compile dependency. Loom supplies the mapped Minecraft classes;
NightConfig and annotation types are compile-only dependencies.

The Fabric root project owns registration, events, transfer adapters, Tom
integration, commands and the Fabric aggregate selftest. Its release and source
JARs include the shared output. Resources stay in the Fabric distribution until
a second loader distribution is introduced.

`verifySharedBoundary` checks all common sources and compiled constant pools,
including reflection strings, and runs as part of root `check`/`build`.
