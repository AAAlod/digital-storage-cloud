# Changelog

## 1.1.6 - 2026-08-23

- Fix production startup by remapping the connector-staggering redirect's
  Minecraft `World.getTime()` target while keeping Tom's method name unmapped.
- Make the optional connector-staggering optimization fail soft if a future
  Tom's Storage release changes the targeted invocation.

## 1.1.5 - 2026-08-23

- Optimize the public project icon from 1254x1254 to 256x256 while preserving
  the same artwork, reducing the image size by more than 90%.

## 1.1.4 - 2026-08-23

- Add the public project logo as the Fabric metadata icon and README identity.
- Link the project homepage, source repository and issue tracker from the Mod
  metadata.
- Add a player-facing README introduction with features, dependencies and
  installation guidance before the technical architecture documentation.

## 1.1.3 - 2026-08-23

- Move the exact Tom's Simple Storage 1.7.1 compile-time dependency into
  `libs/` without bundling it into the distributable JAR.
- Keep the dependency license, upstream source and SHA-256 explicitly recorded
  in the third-party notices.

## 1.1.2 - 2026-08-23

- Standardize the product identity as Digital Storage Cloud across the Gradle
  project, distributable JAR and embedded license filename.
- Make `/digitalstorage` the primary command and `/dsc` its short alias; replace
  the obsolete compatibility report with required-integration diagnostics.
- Apply one Volume deletion policy in the GUI and commands: the owned Volume
  must be empty and have no loaded accessor mounts.
- Require Tom's Simple Storage 1.7.1 and all core mixins, including hopper
  optimization, connector staggering, topology tracking and Volume deduplication.
- Add the Advanced Inventory Hopper with a default 64-item batch alongside the
  standard Tom hopper's 16-item batch.
- Deduplicate Tom network endpoints by Volume UUID so multiple accessors expose
  one canonical digital storage endpoint.
- Standardize all default tier upgrades on vanilla diamonds and keep
  datapack-defined tiers as the runtime source of truth.
- Make mined accessors always drop without controller or Volume binding data.
- Remove unused configuration fields and the unreleased `VariantCapacity`
  migration while retaining schema guards, quarantine and missing-tier safety.
- Rename internal accessor classes and advanced-hopper texture resources to
  match their final roles.
- Rework network performance and capacity wording for players in English and
  Simplified Chinese.
- Correct documentation for the Accessor recipe: 4 gold ingots, 3 redstone,
  1 beacon and 1 Tom inventory cable connector produce 4 accessors.
