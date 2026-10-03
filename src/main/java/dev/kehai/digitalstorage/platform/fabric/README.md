# Fabric adapters

This package owns loader events, networking, resource reload, item-key
conversion and participation in Fabric Transfer transactions. `tom/` reads Tom
topology and manages migration world/player lifetime; `mixin/` contains its
Fabric-specific injection targets. `hopper/` executes transactional hopper
fast paths and filtered cursors.

Shared analysis and migration policies consume `InventoryEndpoint`,
`TopologyToken` and `InventoryTransferExecutor`. `FabricNetworkServices` is
installed by the loader entry point for shared screen operations. Batch,
backoff and scan timing calculations belong to `hopper/HopperPolicy` outside
this platform package.

These adapters preserve original filtering, leave-one and directional wrappers.
Digital storage wrappers share the canonical ledger identity. Transfer commits
only after matching insertion; a partial insert rolls both sides back. These
guarantees rely on Fabric transactions and must be implemented separately for
Forge handlers.
