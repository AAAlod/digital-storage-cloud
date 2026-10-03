# Tom's Storage integration and migration

`NetworkAnalysis` scores network snapshots and recommends candidates using
`ItemKey` and `InventoryEndpoint`. `MigrationTask` owns scan budgets, progress
and topology checks; it delegates settled transfers to `InventoryTransferExecutor`.
These policies do not import loader inventory or Tom types.

`NetworkServices` lets the screen call the loader-installed backend without
depending on its implementation. Fabric discovery, lifetime and telemetry live
in [platform/fabric/tom](../platform/fabric/tom/); loader-specific mixins live in
[platform/fabric/mixin](../platform/fabric/mixin/). Tom's Storage 1.7.1 is the
verification baseline.

Network aggregation deduplicates canonical digital storage, while endpoint
diagnostics still count accessor aliases. Migration excludes digital storage
sources and transfers extraction/insertion in one Fabric transaction, committing
only when the full amount moves.

`TomStorageIdentity` recognizes supported physical wrappers across network
rebuilds without inspecting item contents. Unknown wrappers keep conservative
object identity; do not equate arbitrary filtered wrappers merely because they
share a backing inventory. Job liveness must still detect invalid topology or
unavailable endpoints, and cancellation must not lose items.

Use `/digitalstorage selftest` for built-in integration regressions and
`/digitalstorage diagnostics` for current status. `TomPerformanceBenchmark`
backs the operator benchmark command; benchmark results depend on workload and
are not universal server performance guarantees. Storage ownership and
persistence are described in the adjacent [storage module](../storage/README.md).
