package dev.kehai.digitalstorage.screen;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.optimization.BatchTransfer;
import dev.kehai.digitalstorage.optimization.BatchTransfers;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

/** Menu-scoped preview authority. Client indexes never substitute an item identity. */
public final class BatchScreenSession {
    private UUID previewId = BatchScreenProtocol.EMPTY, volumeId;
    private BatchTransfer.Route route;
    private List<BatchTransfer.Entry> entries = List.of();
    private java.util.Map<Integer, Long> recommended = java.util.Map.of();
    private boolean recommendedMode;
    private boolean exporting;
    private long expires, nextPreview, nextRequest;
    private int lastSerial;
    private dev.kehai.digitalstorage.storage.DigitalStorageRecord record;

    BatchScreenProtocol.Reply handle(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor, BatchScreenProtocol.Request request) {
        String detail = "";
        List<BatchScreenProtocol.Row> rows = List.of();
        int pages = 0, page = 0;
        var volume = accessor == null ? null : accessor.getVolume();
        long tick = player.getServer().getTickCount();
        if (request.serial() <= lastSerial) return null;
        lastSerial = request.serial();
        try {
            if (volume == null || !volume.ownerId().equals(player.getUUID())) throw new IllegalStateException("not_owner");
            if (request.recommended() && (!request.query().isBlank() || !request.matches().isEmpty()))
                throw new IllegalStateException("invalid_request");
            if (tick < nextRequest) throw new IllegalStateException("wait");
            nextRequest = tick + 1;
            var task = BatchTransfers.get(player.getServer(), volume.id());
            if (request.action() == BatchScreenProtocol.STOP) {
                if (task == null || !task.id().equals(request.token())) throw new IllegalStateException("preview_changed");
                BatchTransfers.cancel(player, volume.id());
            } else if (request.action() == BatchScreenProtocol.START) {
                requirePreview(volume.id(), request.token(), tick);
                if (request.recommended() != recommendedMode) throw new IllegalStateException("preview_changed");
                if ((!recommendedMode && request.picks().isEmpty()) || request.picks().size() > BatchTransfer.MAX_ENTRIES + 1) throw new IllegalStateException("nothing_selected");
                var selectedAmounts = new java.util.LinkedHashMap<Integer, Long>();
                if (recommendedMode) selectedAmounts.putAll(recommended);
                boolean all = !recommendedMode && !request.picks().isEmpty() && request.picks().get(0).index() == -1 && request.picks().get(0).amount() == 1;
                if (all) for (int i = 0; i < entries.size(); i++) if (matches(entries.get(i), request)) selectedAmounts.put(i, entries.get(i).amount());
                var seen = new java.util.HashSet<Integer>();
                for (var pick : request.picks()) {
                    if (all && pick.index() == -1 && seen.isEmpty()) { seen.add(-1); continue; }
                    if (!seen.add(pick.index()) || pick.index() < 0 || pick.index() >= entries.size()) throw new IllegalStateException("preview_changed");
                    var entry = entries.get(pick.index());
                    if (pick.amount() < 0 || !all && !recommendedMode && pick.amount() == 0 || pick.amount() > entry.amount()) throw new IllegalStateException("invalid_amount");
                    if (pick.amount() == 0) selectedAmounts.remove(pick.index()); else selectedAmounts.put(pick.index(), pick.amount());
                }
                var selected = new java.util.ArrayList<BatchTransfer.Entry>();
                for (var pick : selectedAmounts.entrySet()) {
                    var entry = entries.get(pick.getKey());
                    if (exporting && record.storage().amountOf(entry.key()) < pick.getValue()) throw new IllegalStateException("preview_changed");
                    selected.add(new BatchTransfer.Entry(entry.key(), pick.getValue()));
                }
                if (selected.isEmpty()) throw new IllegalStateException("nothing_selected");
                var batch = new BatchTransfer(record, route, selected, exporting);
                if (!BatchTransfers.start(player, volume.id(), batch)) throw new IllegalStateException("already_running");
                previewId = BatchScreenProtocol.EMPTY;
            } else if (request.action() == BatchScreenProtocol.PREVIEW || request.action() == BatchScreenProtocol.PAGE) {
                if (task != null && task.active()) return reply(request, task.id(), task.exporting(), 0, 0, rows, task.state(), task.moved(), task.total(), task.detail(), "", volume);
                if (request.action() == BatchScreenProtocol.PREVIEW) {
                    if (tick < nextPreview) throw new IllegalStateException("wait");
                    nextPreview = tick + 10;
                    previewId = BatchScreenProtocol.EMPTY;
                    route = BatchTransfers.open(player, accessor); record = volume.record();
                    exporting = request.exporting(); volumeId = volume.id();
                    entries = BatchTransfer.preview(record, route, exporting, request.tools());
                    recommendedMode = request.recommended(); recommended = java.util.Map.of();
                    if (recommendedMode) {
                        if (exporting) throw new IllegalStateException("no_recommendation");
                        var report = dev.kehai.digitalstorage.optimization.NetworkServices.get().analyze(accessor);
                        if (!report.available()) throw new IllegalStateException("no_recommendation");
                        if (report.targetEndpointCount() > 1) throw new IllegalStateException("duplicate_target");
                        var selected = intersectRecommendations(entries, report.candidates());
                        if (selected.isEmpty()) throw new IllegalStateException("no_recommendation");
                        recommended = java.util.Map.copyOf(selected);
                    }
                    previewId = UUID.randomUUID(); expires = tick + 6000;
                } else { requirePreview(volume.id(), request.token(), tick); if (request.recommended() != recommendedMode) throw new IllegalStateException("preview_changed"); }
                var paginated = new java.util.ArrayList<List<BatchScreenProtocol.Row>>();
                var current = new java.util.ArrayList<BatchScreenProtocol.Row>(); int bytes = 0;
                // Sort presentation indexes, never the authoritative identities used by picks.
                var indexes = new java.util.ArrayList<Integer>();
                for (int i = 0; i < entries.size(); i++) indexes.add(i);
                java.util.Comparator<Integer> byAmount = java.util.Comparator.comparingLong(i -> entries.get(i).amount());
                indexes.sort((request.ascending() ? byAmount : byAmount.reversed()).thenComparingInt(i -> i));
                for (int i : indexes) {
                    var entry = entries.get(i); var icon = entry.key().toStack(1);
                    if (!matches(entry, request)) continue;
                    int size = entry.key().tagBytes() + 1024;
                    if (size > 512_000) throw new IllegalStateException("item_data_too_large");
                    if (!current.isEmpty() && (current.size() == 24 || bytes + size > 512_000)) {
                        paginated.add(List.copyOf(current)); current.clear(); bytes = 0;
                    }
                    current.add(new BatchScreenProtocol.Row(i, icon, entry.amount(), entry.key().maximumStackSize() == 1, recommended.getOrDefault(i, 0L))); bytes += size;
                }
                if (!current.isEmpty()) paginated.add(List.copyOf(current));
                pages = paginated.size(); page = Math.max(0, Math.min(request.page(), pages - 1));
                rows = pages == 0 ? List.of() : paginated.get(page);
                var response = reply(request, previewId, exporting, page, pages, rows, "PREVIEW", 0, 0, "", route.location(), volume);
                var included = entries.stream().filter(entry -> matches(entry, request)).toList();
                return new BatchScreenProtocol.Reply(response.syncId(), response.serial(), response.token(), exporting, page, pages,
                        rows, "PREVIEW", 0, 0, "", route.location(), volume.id(), response.toolsStored(), included.size(), included.stream().mapToLong(BatchTransfer.Entry::amount).sum(),
                        recommendedMode, recommended.size(), recommended.values().stream().mapToLong(Long::longValue).sum());
            } else if (request.action() != BatchScreenProtocol.POLL) throw new IllegalStateException("invalid_request");
            task = BatchTransfers.get(player.getServer(), volume.id());
            if (task != null && (task.active() || previewId.equals(BatchScreenProtocol.EMPTY)))
                return reply(request, task.id(), task.exporting(), 0, 0, rows, task.state(), task.moved(), task.total(), task.detail(), "", volume);
            return reply(request, previewId, exporting, 0, 0, rows, "IDLE", 0, 0, "", "", volume);
        } catch (RuntimeException failure) {
            detail = failure instanceof IllegalStateException && failure.getMessage() != null
                    && failure.getMessage().matches("[a-z_]+") ? failure.getMessage() : "transfer_failed";
            return reply(request, previewId, request.exporting(), page, pages, rows, "ERROR", 0, 0, detail, "", volume);
        }
    }
    private void requirePreview(UUID volume, UUID token, long tick) {
        if (!previewId.equals(token) || token.equals(BatchScreenProtocol.EMPTY) || !volume.equals(volumeId)
                || tick > expires || route == null || !route.current().getAsBoolean()) throw new IllegalStateException("preview_changed");
    }
    private static java.util.Map<Integer, Long> intersectRecommendations(List<BatchTransfer.Entry> entries,
            List<dev.kehai.digitalstorage.optimization.NetworkAnalysis.Candidate> candidates) {
        var byKey = new java.util.HashMap<dev.kehai.digitalstorage.storage.ItemKey, Long>();
        for (var candidate : candidates) byKey.put(candidate.variant(), candidate.amount());
        var selected = new java.util.LinkedHashMap<Integer, Long>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            long amount = Math.min(entry.amount(), byKey.getOrDefault(entry.key(), 0L));
            if (amount > 0) selected.put(i, amount);
        }
        return selected;
    }
    private static BatchScreenProtocol.Reply reply(BatchScreenProtocol.Request request, UUID token, boolean exporting,
            int page, int pages, List<BatchScreenProtocol.Row> rows, String state, long moved, long total,
            String detail, String location, dev.kehai.digitalstorage.storage.StorageVolume volume) {
        long tools = 0;
        if (volume != null) for (var entry : volume.record().storage())
            if (entry.getResource().maximumStackSize() == 1) tools += entry.getAmount();
        return new BatchScreenProtocol.Reply(request.syncId(), request.serial(), token, exporting, page, pages,
                rows, state, moved, total, detail, location, volume == null ? BatchScreenProtocol.EMPTY : volume.id(), tools, 0, 0);
    }
    private static boolean matches(BatchTransfer.Entry entry, BatchScreenProtocol.Request request) {
        String query = request.query().strip().toLowerCase(java.util.Locale.ROOT);
        var id = BuiltInRegistries.ITEM.getKey(entry.key().item());
        return query.isEmpty() || request.matches().contains(id) || id.toString().contains(query)
                || entry.key().toStack(1).getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains(query);
    }
    /** Isolated world fixture: one existing variant remains eligible while the NBT budget excludes a new variant. */
    public static void runRecommendedSubsetSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor,
            net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
        var config = dev.kehai.digitalstorage.config.DigitalStorageConfig.get();
        int previousBudget = config.maxVolumeVariantNbtBytes;
        var oak = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.OAK_PLANKS);
        var dirt = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.DIRT);
        var record = accessor.getRecord();
        try {
            record.storage().load(oak, 1);
            chest.clearContent();
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 64));
            chest.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 64));
            config.maxVolumeVariantNbtBytes = 0;
            var session = new BatchScreenSession();
            var preview = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 1,
                    BatchScreenProtocol.PREVIEW, BatchScreenProtocol.EMPTY, false, false, 0, "", List.of(), List.of(), true, true));
            expect(preview.state().equals("PREVIEW") && preview.available() == 128 && preview.suggestedCount() == 1
                    && preview.suggestedTotal() == 64, "NBT budget did not restrict real network recommendations: " + preview.detail());
            session.nextRequest = 0;
            var started = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 2,
                    BatchScreenProtocol.START, preview.token(), false, false, 0, "", List.of(), List.of(), true, true));
            expect(started.state().equals("RUNNING") && started.total() == 64, "Recommended subset did not start");
            var task = BatchTransfers.get(player.getServer(), accessor.getVolume().id());
            for (int i = 0; i < 100 && task.active(); i++) task.tick(128);
            expect(task.state().equals("COMPLETE") && task.moved() == 64 && record.storage().amountOf(oak) == 65
                    && record.storage().amountOf(dirt) == 0 && chest.getItem(0).isEmpty() && chest.getItem(1).getCount() == 64,
                    "Recommended subset lost, duplicated or moved the excluded variant");
        } finally {
            config.maxVolumeVariantNbtBytes = previousBudget;
            try (var tx = dev.kehai.digitalstorage.storage.LedgerTransaction.open()) {
                record.storage().extract(oak, 65, tx); tx.commit();
            }
            chest.clearContent();
        }
    }
    public static void runDuplicateTargetSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        var session = new BatchScreenSession();
        var recommended = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 1,
                BatchScreenProtocol.PREVIEW, BatchScreenProtocol.EMPTY, false, false, 0, "", List.of(), List.of(), true, true));
        expect(recommended.state().equals("ERROR") && recommended.detail().equals("duplicate_target")
                && recommended.rows().isEmpty() && recommended.suggestedCount() == 0, "Duplicate target recommendation response: "
                + recommended.state() + "/" + recommended.detail() + ", endpoints="
                + dev.kehai.digitalstorage.optimization.NetworkServices.get().analyze(accessor).targetEndpointCount());
        session.nextRequest = session.nextPreview = 0;
        var manual = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 2,
                BatchScreenProtocol.PREVIEW, BatchScreenProtocol.EMPTY, false, false, 0, "", List.of(), List.of(), true, false));
        expect(manual.state().equals("PREVIEW") && !manual.recommended(), "Duplicate target blocked manual preview");
    }
    public static void runRecommendedCapacitySelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor,
            net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
        var record = accessor.getRecord();
        var fillers = new java.util.ArrayList<dev.kehai.digitalstorage.storage.ItemKey>();
        var oak = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.OAK_PLANKS);
        var dirt = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.DIRT);
        try {
            for (int i = 0; i < record.variantCapacity() - 1; i++) {
                var tag = new net.minecraft.nbt.CompoundTag(); tag.putInt("CapacityFixture", i);
                var key = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE, tag);
                record.storage().load(key, 1); fillers.add(key);
            }
            chest.clearContent(); chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 64));
            chest.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 64));
            var session = new BatchScreenSession();
            var preview = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 1,
                    BatchScreenProtocol.PREVIEW, BatchScreenProtocol.EMPTY, false, false, 0, "", List.of(), List.of(), true, true));
            expect(preview.state().equals("PREVIEW") && preview.available() == 128 && preview.suggestedCount() == 1
                    && preview.suggestedTotal() == 64, "Capacity budget did not restrict the actual recommendation");
            session.nextRequest = 0;
            var started = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 2,
                    BatchScreenProtocol.START, preview.token(), false, false, 0, "", List.of(), List.of(), true, true));
            expect(started.state().equals("RUNNING"), "Capacity-limited recommendation did not start");
            var task = BatchTransfers.get(player.getServer(), accessor.getVolume().id());
            for (int i = 0; i < 100 && task.active(); i++) task.tick(128);
            long stored = record.storage().amountOf(oak) + record.storage().amountOf(dirt);
            int remaining = chest.getItem(0).getCount() + chest.getItem(1).getCount();
            expect(task.state().equals("COMPLETE") && stored == 64 && remaining == 64
                    && (chest.getItem(0).isEmpty() != chest.getItem(1).isEmpty()),
                    "Capacity-limited recommendation moved an excluded item or failed conservation");
        } finally {
            try (var tx = dev.kehai.digitalstorage.storage.LedgerTransaction.open()) {
                record.storage().extract(oak, 64, tx); record.storage().extract(dirt, 64, tx);
                for (var key : fillers) record.storage().extract(key, 1, tx);
                tx.commit();
            }
            chest.clearContent();
        }
    }
    public static void runNetworkPreviewSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor,
            Runnable rebuild, Runnable removeContainer, boolean useRecommendation) {
        var session = new BatchScreenSession();
        var preview = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 1,
                BatchScreenProtocol.PREVIEW, BatchScreenProtocol.EMPTY, false, false, 0, "", List.of(), List.of(), false, useRecommendation));
        expect(preview.state().equals("PREVIEW") && preview.available() == 576, "Physical chest preview failed: " + preview);
        if (useRecommendation) expect(preview.recommended() && preview.suggestedCount() == 1
                && preview.suggestedTotal() == 576 && preview.rows().get(0).suggestedAmount() == 576,
                "Physical network recommendation did not match preview");
        rebuild.run();
        session.nextRequest = 0;
        var started = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 2,
                BatchScreenProtocol.START, preview.token(), false, false, 0, "", List.of(),
                useRecommendation ? List.of() : List.of(new BatchScreenProtocol.Pick(-1, 1)), false, useRecommendation));
        expect(started.state().equals("RUNNING"), "All-selected import rejected after scanner rebuild: " + started.detail());
        var task = BatchTransfers.get(player.getServer(), accessor.getVolume().id());
        for (int i = 0; i < 100 && task.active(); i++) task.tick(128);
        expect(task.state().equals("COMPLETE") && task.moved() == 576
                && accessor.getRecord().storage().totalItemCount() == 576, "Chest import did not settle: " + task.detail());
        session.nextRequest = session.nextPreview = 0;
        var refreshed = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 3,
                BatchScreenProtocol.PREVIEW, BatchScreenProtocol.EMPTY, false, false, 0, "", List.of(), List.of()));
        expect(refreshed.state().equals("PREVIEW") && refreshed.available() == 0, "Post-import preview still contains chest items");
        removeContainer.run(); session.nextRequest = 0;
        var stale = session.handle(player, accessor, new BatchScreenProtocol.Request(207, 4,
                BatchScreenProtocol.PAGE, refreshed.token(), false, false, 0, "", List.of(), List.of()));
        expect(stale.state().equals("ERROR") && stale.detail().equals("preview_changed"), "Removed chest retained preview authority");
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Physical batch preview regression passed: {} selection, 5 scanner rebuilds, 576 imported, refreshed chest empty, removed container revokes preview", useRecommendation ? "recommended" : "manual all");
    }
    static void runSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        var volume = accessor.getVolume();
        var session = new BatchScreenSession();
        var stone = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE);
        var dirt = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.DIRT);
        var intersection = intersectRecommendations(List.of(new BatchTransfer.Entry(stone, 5), new BatchTransfer.Entry(dirt, 7)),
                List.of(new dev.kehai.digitalstorage.optimization.NetworkAnalysis.Candidate(stone, 9, 1, true)));
        expect(intersection.equals(java.util.Map.of(0, 5L)), "Recommendations ignored preview identity or quantity limit");
        boolean[] current = {true};
        var route = new BatchTransfer.Route(List.of(), () -> current[0], "fixture");
        session.previewId = UUID.randomUUID(); session.volumeId = volume.id(); session.record = volume.record();
        session.route = route; session.exporting = true; session.expires = Long.MAX_VALUE;
        session.entries = List.of(new BatchTransfer.Entry(stone, 5));
        volume.record().storage().load(stone, 5);
        UUID preview = session.previewId;
        int serial = 1;
        var large = new java.util.ArrayList<BatchTransfer.Entry>();
        for (int i = 0; i < 1024; i++) {
            var tag = new net.minecraft.nbt.CompoundTag(); tag.putInt("BatchPageFixture", i);
            large.add(new BatchTransfer.Entry(dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE, tag), i + 1));
        }
        session.entries = large;
        var page = session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.PAGE, preview, List.of()));
        expect(page.rows().size() == 24 && page.pages() == 43 && page.entryCount() == 1024 && page.available() == 524800
                && page.rows().get(23).index() == 23, "Large preview lost pagination or exact quantities");
        session.nextRequest = 0;
        var descending = session.handle(player, accessor, new BatchScreenProtocol.Request(207, serial++,
                BatchScreenProtocol.PAGE, preview, true, false, 0, "", List.of(), List.of(), false));
        expect(descending.rows().get(0).index() == 1023 && descending.rows().get(0).amount() == 1024
                && descending.rows().get(23).index() == 1000 && descending.pages() == 43,
                "Descending quantity sort did not cover the full preview or changed identity indexes");
        session.nextRequest = 0;
        var secondPage = session.handle(player, accessor, new BatchScreenProtocol.Request(207, serial++,
                BatchScreenProtocol.PAGE, preview, true, false, 1, "", List.of(), List.of(), false));
        expect(secondPage.rows().get(0).index() == 999 && secondPage.available() == 524800,
                "Quantity sort lost order or totals across pages");
        session.entries = List.of(new BatchTransfer.Entry(stone, 5)); session.nextRequest = 0;
        session.expires = player.getServer().getTickCount() - 1;
        expect(session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.START, preview, List.of(new BatchScreenProtocol.Pick(0, 2)))).detail().equals("preview_changed"), "Expired preview started a task");
        session.expires = Long.MAX_VALUE; session.nextRequest = 0; current[0] = false;
        expect(session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.START, preview, List.of(new BatchScreenProtocol.Pick(0, 2)))).detail().equals("preview_changed"), "Revoked network started a preview task");
        current[0] = true; session.nextRequest = 0;
        var forged = session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.START, UUID.randomUUID(), List.of(new BatchScreenProtocol.Pick(0, 2))));
        expect(forged.detail().equals("preview_changed") && BatchTransfers.get(player.getServer(), volume.id()) == null, "Forged preview token started a batch");
        session.nextRequest = 0;
        var oversized = session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.START, preview, List.of(new BatchScreenProtocol.Pick(0, 6))));
        expect(oversized.detail().equals("invalid_amount"), "Client quantity exceeded immutable preview");
        session.nextRequest = 0;
        var duplicate = session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.START, preview, List.of(new BatchScreenProtocol.Pick(0, 2), new BatchScreenProtocol.Pick(0, 3))));
        expect(duplicate.detail().equals("preview_changed"), "Duplicate item picks were combined");
        session.nextRequest = 0;
        var started = session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.START, preview, List.of(new BatchScreenProtocol.Pick(0, 2))));
        expect(started.state().equals("RUNNING") && started.total() == 2, "Valid quantity selection did not start");
        session.nextRequest = 0;
        var replay = session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.START, preview, List.of(new BatchScreenProtocol.Pick(0, 2))));
        expect(replay.detail().equals("preview_changed") && BatchTransfers.get(player.getServer(), volume.id()).id().equals(started.token()), "Consumed preview started a second batch");
        session.nextRequest = 0;
        var wrongStop = session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.STOP, UUID.randomUUID(), List.of()));
        expect(wrongStop.detail().equals("preview_changed") && BatchTransfers.active(player.getServer(), volume.id()), "Stale stop cancelled another task");
        session.nextRequest = 0;
        var stopped = session.handle(player, accessor, testRequest(serial++, BatchScreenProtocol.STOP, started.token(), List.of()));
        expect(stopped.state().equals("CANCELLED") && volume.record().storage().amountOf(stone) == 5, "Stop moved or erased source items");
        expect(session.handle(player, accessor, testRequest(serial - 1, BatchScreenProtocol.STOP, started.token(), List.of())) == null, "Replayed request serial was processed");
        session.previewId = UUID.randomUUID(); session.exporting = false; session.recommendedMode = true;
        session.entries = large; session.recommended = java.util.Map.of(0, 1L, 24, 20L, 1023, 100L);
        session.nextRequest = 0;
        var filteredRecommendation = session.handle(player, accessor, new BatchScreenProtocol.Request(207, serial++,
                BatchScreenProtocol.START, session.previewId, false, false, 0, "stone", List.of(), List.of(), true, true));
        expect(filteredRecommendation.detail().equals("invalid_request"), "Recommended mode accepted a filtered request");
        session.nextRequest = 0;
        var suggestedPage = session.handle(player, accessor, new BatchScreenProtocol.Request(207, serial++,
                BatchScreenProtocol.PAGE, session.previewId, false, false, 1, "", List.of(), List.of(), true, true));
        expect(suggestedPage.suggestedCount() == 3 && suggestedPage.suggestedTotal() == 121
                && suggestedPage.rows().get(0).index() == 24 && suggestedPage.rows().get(0).suggestedAmount() == 20,
                "Recommendation totals or page marks were lost");
        session.nextRequest = 0;
        var deniedAll = session.handle(player, accessor, new BatchScreenProtocol.Request(207, serial++,
                BatchScreenProtocol.START, session.previewId, false, false, 0, "", List.of(),
                List.of(new BatchScreenProtocol.Pick(-1, 1)), true, true));
        expect(deniedAll.detail().equals("preview_changed"), "Recommended mode accepted all-selection sentinel");
        session.nextRequest = 0;
        var suggestedStart = session.handle(player, accessor, new BatchScreenProtocol.Request(207, serial++,
                BatchScreenProtocol.START, session.previewId, false, false, 0, "", List.of(),
                List.of(new BatchScreenProtocol.Pick(0, 0), new BatchScreenProtocol.Pick(24, 12)), true, true));
        expect(suggestedStart.state().equals("RUNNING") && suggestedStart.total() == 112,
                "Recommended cross-page selection or overrides failed");
        BatchTransfers.cancel(player, volume.id());
        session.recommendedMode = false;
        var offline = new BatchTransfer(volume.record(), route, List.of(new BatchTransfer.Entry(stone, 1)), true);
        expect(BatchTransfers.start(player, volume.id(), offline), "Stopped batch prevented a new task");
        BatchTransfers.tick(player.getServer());
        expect(offline.detail().equals("player_disconnected") && offline.moved() == 0, "Offline owner continued a background batch");
        try (var tx = dev.kehai.digitalstorage.storage.LedgerTransaction.open()) { volume.record().storage().extract(stone, 5, tx); tx.commit(); }
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Batch preview fixture passed: 1024 identities/page totals, token/quantity/duplicate guards, consumed preview, task-specific stop, replayed serial and offline lifecycle");
    }
    private static BatchScreenProtocol.Request testRequest(int serial, int action, UUID token, List<BatchScreenProtocol.Pick> picks) {
        return new BatchScreenProtocol.Request(207, serial, action, token, true, false, 0, "", List.of(), picks);
    }
    private static void expect(boolean condition, String detail) { if (!condition) throw new IllegalStateException(detail); }
}
