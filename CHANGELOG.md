# Changelog

## 1.1.11 - 2026-08-29

- Explain why non-empty storage volumes cannot be deleted with localized button
  tooltips while retaining authoritative server-side deletion checks.
- Keep the configured inventory key from closing the accessor screen while the
  storage-volume name field is focused, without changing Escape behavior.

## 1.1.10 - 2026-08-27

- Add an owner-controlled per-Volume policy for accepting unstackable items,
  with a server-wide capability gate, legacy configuration migration, persistent
  state, immediate enforcement and consistent Tom analysis/migration behavior.
- Add a dedicated Digital Storage Cloud creative inventory tab instead of placing
  the Mod's items in the vanilla Redstone Blocks tab.
- Rename the Simplified Chinese display name of the Advanced Inventory Hopper to
  `高级存储漏斗`.

## 1.1.9 - 2026-08-26

- Fix Accessor GUI text clipping in English, Simplified Chinese, and long-value
  edge cases with right-aligned fitted values and flow-based wrapped messages.
- Move the recommended-migration explanation into the Migrate button tooltip,
  and shorten labels that must remain fully visible in the fixed-size layout.

## 1.1.8 - 2026-08-26

- Keep Tom's Simple Storage as a required dependency while removing the exact
  1.7.1 metadata pin for experimental compatibility testing with other versions.
- Replace the Digital Storage Accessor recipe's four gold ingots with four Tom
  Inventory Trims, and its three redstone dust with two diamonds and one comparator.
- Add the Simplified Chinese Mod Menu display name `汤姆的简易存储：云上加仓`
  while retaining `Digital Storage Cloud` as the English and metadata fallback.

## 1.1.7 - 2026-08-25

- Make `/digitalstorage stats` avoid cold-loading every Volume; it now reports
  how many content summaries are known and reserves exact full inspection for
  the explicit `/digitalstorage stats deep` command.
- Document and regression-test the intentional `2,147,483,647` item safety
  ceiling per exact variant while preserving extraction of stored over-limit data.
- Include the required marker-compatible Tom's Storage copy in Loom development
  runs without changing or bundling the distributable dependency.
- Redesign the accessor screen around storage, network, upgrade and optimization
  summaries; hide healthy low-level telemetry, clarify Volume names and show
  actionable warnings or migration progress only when relevant.
- Analyze a bound Tom network once when the accessor screen opens and deliver the
  result in the first server state update, while retaining manual refresh.

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
