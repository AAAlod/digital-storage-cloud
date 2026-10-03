# Storage and persistence

`DigitalStorageState` owns the server's player accounts and volumes.
`StorageVolume` owns item contents and capacity; accessor blocks only retain
controller and volume IDs. Accessors bound to the same volume expose the same
canonical platform storage adapter so network aggregation can deduplicate it.

`VolumeLedger` owns quantities, policy, metrics and incremental snapshots without
loader APIs. `ItemKey` and `ItemKeyCodec` preserve the existing variant identity
and stored encoding. `MutationScope` enlists per-entry and metric snapshots;
`LedgerTransaction` provides local nested transactions for shared ledger work.
It does not make arbitrary external inventories transactional.

The Fabric adapter is `platform/fabric/FabricDigitalItemStorage`; its bridges
participate in external Fabric transactions and notify only on final commit. Provisional
mutation and rollback must invalidate incremental snapshot iteration even when
committed content has not changed. Keep extraction possible for existing items
when insertion policy becomes more restrictive.

Persistence lives under `<world>/digitalstorage/`, with compressed account and
volume NBT files. Dirty data is snapshotted on the server thread and written by
one ordered background writer; shutdown flushes pending work. Malformed records
are quarantined individually, while newer schemas must not be rewritten by an
older mod. Preserve these boundaries when changing storage or persistence.

`DigitalItemStorageSelfTest` is the storage regression entry point, invoked by
the operator command `/digitalstorage selftest`. Network migration is described
in the adjacent [optimization module](../optimization/README.md).
