package dev.kehai.digitalstorage.forge.tom;

import com.tom.storagemod.util.FilteredInventoryHandler;
import com.tom.storagemod.util.MultiItemHandler;
import dev.kehai.digitalstorage.forge.ForgeDigitalItemStorage;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

public final class ForgeTomAnalysisSelfTest {
    private ForgeTomAnalysisSelfTest() { }
    public static void run() {
        var record = DigitalStorageRecord.createNew(() -> { });
        var target = ForgeDigitalItemStorage.of(record.storage());
        var first = LazyOptional.<IItemHandler>of(() -> target.guarded(() -> true));
        var physical = new ItemStackHandler(3);
        physical.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        physical.setStackInSlot(2, new ItemStack(Items.STONE, 20));
        var physicalCapability = LazyOptional.<IItemHandler>of(() -> physical);
        var connector = new com.tom.storagemod.tile.InventoryConnectorBlockEntity(net.minecraft.core.BlockPos.ZERO,
                com.tom.storagemod.Content.connector.get().defaultBlockState());
        var network = (MultiItemHandler) connector.getInventory().orElseThrow(() ->
                new IllegalStateException("Actual Tom connector capability missing"));
        network.add(first);
        network.add(physicalCapability);
        network.refresh();
        var current = new AtomicReference<IItemHandler>(network);
        var report = ForgeTomTopology.analyze(record, current::get);
        expect(report.available() && report.physicalInventories() == 1 && report.totalViews() == 3
                        && report.nonEmptyViews() == 2 && report.targetEndpointCount() == 1
                        && report.candidates().size() == 1 && report.candidates().get(0).amount() == 30,
                "Forge topology did not feed shared analysis correctly");
        expect(report.topology().isCurrent() && report.sourceEndpoints().get(0).resolve() != null,
                "Fresh topology token or source reference failed");
        physical.setStackInSlot(0, new ItemStack(Items.STONE, 11));
        expect(report.topology().isCurrent(), "Inventory contents invalidated a structural topology token");
        network.refresh();
        expect(report.topology().isCurrent(), "Unchanged Tom refresh invalidated its stable handles");
        network.add(LazyOptional.of(() -> target.guarded(() -> true)));
        expect(!report.topology().isCurrent() && report.sourceEndpoints().get(0).resolve() == null,
                "Raw duplicate endpoint did not revoke previous topology/source references");
        var duplicate = ForgeTomTopology.analyze(record, current::get);
        expect(duplicate.targetEndpointCount() == 2 && duplicate.duplicateDigitalEndpoints() == 1
                        && duplicate.candidates().isEmpty(), "Duplicate target still generated migration recommendations");
        network.clear();
        network.add(first);
        network.add(physicalCapability);
        network.add(LazyOptional.of(() -> new FilteredInventoryHandler(target, stack -> true, true)));
        network.refresh();
        var filtered = ForgeTomTopology.analyze(record, current::get);
        expect(filtered.physicalInventories() == 1 && filtered.targetEndpointCount() == 2
                        && filtered.candidates().isEmpty(), "Filtered digital volume was classified as a physical source");
        network.clear();
        network.add(first);
        network.add(physicalCapability);
        network.refresh();
        expect(!report.topology().isCurrent() && report.sourceEndpoints().get(0).resolve() == null,
                "Restored topology reactivated a previously revoked source reference");
        var stable = ForgeTomTopology.analyze(record, current::get);
        physical.setSize(4);
        expect(!stable.topology().isCurrent(), "Physical slot mapping change retained a valid token");
        var resized = ForgeTomTopology.analyze(record, current::get);
        physicalCapability.invalidate();
        expect(!resized.topology().isCurrent(), "Invalid physical capability retained a valid token");
        current.set(null);
        expect(!resized.topology().isCurrent()
                        && !ForgeTomTopology.analyze(record, current::get).available(),
                "Disconnected network remained available");

        var mixed = new MultiItemHandler();
        mixed.add(first);
        mixed.add(LazyOptional.of(() -> new ItemStackHandler(1)));
        mixed.refresh();
        boolean refused = false;
        try { ForgeTomTopology.analyze(record, () -> new FilteredInventoryHandler(mixed, stack -> true, false)); }
        catch (IllegalStateException expected) { refused = true; }
        expect(refused, "Mixed filtered aggregate was flattened into an unsafe migration source");
        expect(record.storage().amountOf(ItemKey.of(Items.STONE)) == 0,
                "Read-only network analysis changed target inventory");
        var connectorReport = ForgeTomTopology.analyze(record, () -> connector.getInventory().orElse(null));
        expect(connectorReport.topology().isCurrent(), "Actual connector inventory could not produce a current token");
        connector.setRemoved();
        expect(!connectorReport.topology().isCurrent(), "Removed Tom connector kept its capability topology active");
    }
    private static void expect(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
