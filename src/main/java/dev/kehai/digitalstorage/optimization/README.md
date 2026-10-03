# Tom's Storage integration and migration

`TomNetworkIntrospection` reads the Tom network; `TomNetworkAnalysis` summarizes
its health and selects migration candidates. `TomMigrationManager` performs
bounded per-tick work. The integration mixins live in the sibling `mixin/`
package; Tom's Storage 1.7.1 is the compile and verification baseline.

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
