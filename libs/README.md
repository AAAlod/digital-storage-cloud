# Local dependency

`toms_storage_fabric-1.20-1.7.1.jar` is the checked-in Tom's Simple Storage
Fabric dependency for Minecraft 1.20.1. A regular clone includes this JAR;
there is no manual download step before building.

`build.gradle` prepares a Loom-compatible copy and extracts the bundled
Cloth Config and Basic Math libraries for development runs. These dependencies
are not bundled into the distributable Digital Storage Cloud JAR. Players
install Tom's Storage separately.

Version, upstream source, SHA-256 and licensing are recorded in
[third-party notices](../THIRD_PARTY_NOTICES.md). The included license is at
[licenses/Toms-Storage-LICENSE](../licenses/Toms-Storage-LICENSE).
