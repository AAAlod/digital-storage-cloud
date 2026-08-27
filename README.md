<p align="center">
  <img src="src/main/resources/assets/digitalstorage/icon.png" alt="Digital Storage Cloud logo" width="240">
</p>

<h1 align="center">Digital Storage Cloud</h1>

<p align="center">
  Player-owned cloud storage for large stackable-item collections, integrated
  directly with Tom's Simple Storage.
</p>

<p align="center">
  <strong>Minecraft 1.20.1 · Fabric · Tom's Simple Storage</strong>
</p>

<p align="center">
  <a href="#ai-assisted-development">
    <img src="https://img.shields.io/badge/development-AI--assisted-6f42c1" alt="AI-assisted development">
  </a>
</p>

## Features

- Player-owned storage volumes with data-driven capacity tiers.
- Lightweight accessors: bind any number of physical blocks to one Volume.
- An Advanced Inventory Hopper with larger atomic transfer batches.
- Tom network deduplication, connector scan staggering and migration tools.
- Sharded, asynchronous persistence with per-file quarantine and schema guards.

## Installation

Install the following on both the server and clients that open the management
screen:

- Fabric Loader 0.15.11 or newer for Minecraft 1.20.1.
- Fabric API for Minecraft 1.20.1.
- Tom's Simple Storage (1.7.1 is the compile and verification baseline; the
  metadata does not pin an exact Tom version).
- Digital Storage Cloud.

Place the downloaded Mod JARs in the instance or server `mods/` directory. Do
not install the `-sources.jar` file.

For commands, recipes, binding, upgrades and troubleshooting, see the
[中文玩家使用指南](PLAYER_GUIDE_zh_CN.md).

## Overview

Digital Storage Cloud is a high-performance Minecraft 1.20.1 Fabric storage
backend for Tom's Simple Storage, not a replacement for Tom's terminal, search,
crafting or network operations. Logical, player-owned volumes hold bulk
stackable items while ordinary chests remain the intended home for tools,
equipment and other special items. Physical blocks are lightweight accessors:
any number of accessors can link to one volume while Tom and Fabric Transfer API
consumers see the same canonical `Storage<ItemVariant>` instance.

## Architecture

```text
Player account
  -> up to 3 storage volumes by default
       -> independent name, tier and item contents
            <- any number of bound accessors
```

- Items and tiers belong to `StorageVolume`, never to a block entity.
- An accessor stores only `controllerId` and `boundVolumeId`.
- The first successful bind sets the controller; merely opening or clicking a
  block does not claim it.
- Only the controller can change or clear an accessor binding.
- A player may bind an accessor only to one of their own volumes.
- Clearing a binding atomically clears both controller and volume IDs.
- Accessors have no tick function and resolve a volume only when accessed.
- Multiple accessors bound to one volume return the identical canonical storage
  object, allowing Tom's merged inventory to deduplicate them.

The default account limit is configured with `defaultVolumesPerPlayer`. Set it
to `0` for unlimited volumes.

Normal chunk save/load keeps an accessor binding. Mining it always drops a clean
item with no controller or Volume ID, so it must be rebound after placement.

## Player commands

```text
/digitalstorage volume create <name>
/digitalstorage volume list
/digitalstorage volume rename <volume UUID> <name>
/digitalstorage volume delete <volume UUID>

/digitalstorage accessor bind <x> <y> <z> <volume UUID>
/digitalstorage accessor clear <x> <y> <z>
/digitalstorage accessor inspect <x> <y> <z>
```

`/dsc` is the short alias. A Volume can be deleted only when it is empty and no
loaded accessor remains bound to it. Operator-only diagnostics remain available:

```text
/digitalstorage stats
/digitalstorage stats deep
/digitalstorage diagnostics
/digitalstorage probe <x> <y> <z>
/digitalstorage selftest
/digitalstorage benchmark
/digitalstorage reloadconfig
/digitalstorage flush
```

## Storage behavior

- Data-driven tiers provide 64, 128, 256, 512 and 1024 variant capacities by
  default.
- Each default upgrade consumes one resource type and follows the capacity
  curve: 16, 32, 64 and 128 diamonds. Datapacks can tune these values for a
  modpack, but upgrade tiers must still use exactly one resource and no XP.
- Each exact item variant intentionally accepts up to `2,147,483,647` items as
  a safety ceiling. Existing stored data above that ceiling remains extractable.
- Insert and extract are transaction-safe, including nested atomic Tom hopper
  transfers.
- `allowUnstackableItems` defaults to `true` and lets each Volume owner explicitly
  opt in to max-stack-size-one items; new Volumes still default to Reject. Legacy
  `rejectUnstackableItems` values are migrated inversely to preserve existing
  server behavior. Extraction is always allowed, including after opting out.
- Item/tag filters and per-variant NBT limits apply when creating a new variant.
- Malformed volumes are quarantined individually and their raw NBT is retained.
- A newer cloud schema is rejected instead of being rewritten by an older Mod.

## Sharded persistence

Storage data no longer uses a monolithic Minecraft `PersistentState`. Every
account and volume has its own compressed NBT file:

```text
<world>/digitalstorage/
  accounts/<player UUID>.dat
  volumes/<volume UUID>.dat
  quarantine/accounts/
  quarantine/volumes/
```

Only dirty accounts and volumes are serialized. By default, dirty snapshots are
batched every 200 server ticks and written by one ordered background writer.
`persistenceFlushIntervalTicks` accepts values from 20 to 12000. Server shutdown
forces a flush and waits for all queued writes.

Incremental volume snapshots keep the normal `persistenceVariantsPerTick`
budget (128 by default). An unfinished hot volume rotates to the back of the
dirty queue so it cannot block cold volumes. If one snapshot restarts four times
because the volume keeps changing, or remains dirty for 600 server ticks, its
next scheduled pass finishes the in-memory snapshot in one server-thread step.
`/digitalstorage stats` reports restart count, forced snapshot count, oldest dirty age,
and content totals already known without loading cold Volumes. Operators can run
`/digitalstorage stats deep` when an exact one-off total justifies loading every Volume.

Each update is written to a temporary file and atomically replaces its target.
A malformed file is moved out of the live directory into `quarantine`; other
accounts and volumes continue loading. A file with a newer schema stops loading
instead, preventing an older Mod from moving or rewriting newer data.

## Tom's Simple Storage

Tom's Simple Storage remains a hard dependency, but release 1.1.8 intentionally
does not enforce an exact Tom version so compatibility with other builds can be
tested. Version 1.7.1 remains the compile and verification baseline. Required integration
mixins batch Basic Inventory Hopper transfers, use direct exact-variant extraction,
back off failed transfers and stagger connector scans. Exact moves use a nested Fabric
transaction so a destination capacity change rolls back the source.

Tom's `MergedStorage` continues trying later physical inventories when Digital
Storage rejects an insertion, so unstackable items fall back to ordinary chests
when those inventories are part of the same network. The runtime self-test
verifies this behavior against the baseline Tom 1.7.1 build. Other Tom versions
are experimental until they pass the same checks.

## Tom network performance and migration

A bound accessor connected through an adjacent Tom inventory cable connector
analyzes the network when its screen opens and shows a relative `0-100 / A-E`
health score. A healthy network stays compact; the screen expands only actionable
duplicate-endpoint, failing-hopper and migration guidance. Low-level counters stay
available through the diagnostic commands instead of filling the player UI.
Digital Storage item quantities do not directly reduce the score and the UI
deliberately avoids absolute MSPT claims. The refresh button requests a new
analysis after topology changes.

Recommendations maximize physical views freed with the target volume's limited
variant budget. Variants already present in the volume are selected first, then
candidates are ordered by views freed and total amount. All Digital Storage
instances are excluded as sources, including instances behind Tom's filtered
storage wrapper.

`migrationViewsScannedPerTick` defaults to `256` and bounds how many physical
network entries one migration job examines per game tick. Each move runs its
source extraction and target insertion in one Fabric transaction and commits
only when the inserted amount equals the extracted amount. Jobs can be cancelled
from the accessor screen. They may continue after the initiating player logs out,
but stop without automatic resume if the accessor, Tom connector, source inventory,
network topology or target becomes unavailable.

## Build

Requires Java 17.

```text
./gradlew build
```

The distributable JAR is written to `build/libs/`.

## AI-assisted development

This project is developed with assistance from AI tools, including OpenAI Codex.
AI assistance is used for implementation, code review, documentation and test
planning. All AI-assisted changes are reviewed and tested by the maintainer
before release, and the maintainer remains responsible for the resulting code,
documentation and published artifacts.
