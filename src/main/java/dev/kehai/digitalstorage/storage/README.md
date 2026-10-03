# Storage and persistence

`DigitalStorageState` owns the server's player accounts and volumes.
`StorageVolume` owns item contents and capacity; accessor blocks only retain
controller and volume IDs. Accessors bound to the same volume expose the same
canonical `DigitalItemStorage` object so network aggregation can deduplicate it.

`DigitalItemStorage` implements Fabric Transfer API transactions. Provisional
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
