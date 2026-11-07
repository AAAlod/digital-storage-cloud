package dev.kehai.digitalstorage.storage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Tests transaction accounting and capture coordination independently of persistence. */
public final class TransactionBudgetSelfTest {
    private TransactionBudgetSelfTest() { }

    public static void run() {
        nestedDeltas();
        concurrentTransactions();
        captureGate();
        captureBlocksNewEntries();
        realLedgerBudgetAndRootIsolation();
        sameVolumeConflictAndRetry();
        concurrentVolumeStress();
        exceptionReleasesVolume();
        distinctVolumesCanOverlap();
        loadedGlobalOverBudget();
        budgetLimitReload();
        SnapshotCoordinatorSelfTest.run();
    }

    private static void budgetLimitReload() {
        var limit = new java.util.concurrent.atomic.AtomicLong(372);
        var budget = new GlobalBudget(186, limit::get);
        try (var tx = LedgerTransaction.open()) { expect(budget.tryReserve(186, tx), "initial dynamic budget rejected"); tx.commit(); }
        limit.set(186);
        try (var tx = LedgerTransaction.open()) {
            expect(!budget.tryReserve(0, tx), "reloaded lower budget accepted an increase");
            budget.release(186, tx); tx.commit();
        }
        expect(budget.committedBytes() == 186 && budget.maxBytes() == 186 && !budget.failed(), "reloaded budget or extraction failed");
        limit.set(372);
        var inFlight = new GlobalBudget(0, limit::get);
        try (var tx = LedgerTransaction.open()) {
            expect(inFlight.tryReserve(372, tx), "pre-reload reservation failed");
            limit.set(186); tx.commit();
        }
        expect(inFlight.committedBytes() == 372 && !inFlight.failed(), "reload made a valid in-flight reservation permanently read-only");
        try (var tx = LedgerTransaction.open()) {
            expect(!inFlight.tryReserve(0, tx), "over-budget increase after reload accepted");
            inFlight.release(186, tx); tx.commit();
        }
        expect(inFlight.committedBytes() == 186 && !inFlight.failed(), "over-budget extraction after reload failed");
    }

    private static void loadedGlobalOverBudget() {
        var stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
        var dirt = ItemKey.of(net.minecraft.world.item.Items.DIRT);
        long limit = ItemKeyCodec.write(stone).sizeInBytes();
        var budget = new GlobalBudget(0, limit); var ledger = new VolumeLedger(() -> { }, 8);
        ledger.load(stone, 5); ledger.load(dirt, 3); ledger.attachAccounting(budget, new TransactionRegistry());
        expect(budget.committedBytes() > limit, "over-budget fixture did not exceed limit");
        try (var tx = LedgerTransaction.open()) {
            expect(ledger.insert(stone, 1, tx) == 0, "over-budget existing variant accepted more items");
            expect(ledger.extract(dirt, 3, tx) == 3, "over-budget extraction rejected"); tx.commit();
        }
        expect(budget.committedBytes() == limit && ledger.amountOf(stone) == 5, "over-budget extraction accounting failed");
    }

    private static void distinctVolumesCanOverlap() {
        var key = ItemKey.of(net.minecraft.world.item.Items.STONE);
        long bytes = ItemKeyCodec.write(key).sizeInBytes();
        var registry = new TransactionRegistry(); var budget = new GlobalBudget(0, 2 * bytes);
        var left = new VolumeLedger(() -> { }, 4); var right = new VolumeLedger(() -> { }, 4);
        left.attachAccounting(budget, registry); right.attachAccounting(budget, registry);
        var both = new CountDownLatch(2); var failure = new AtomicReference<Throwable>();
        java.util.function.Consumer<VolumeLedger> run = ledger -> {
            try (var tx = LedgerTransaction.open()) {
                expect(ledger.insert(key, 1, tx) == 1, "distinct volume insert failed");
                both.countDown(); expect(both.await(3, TimeUnit.SECONDS), "different volumes serialized"); tx.commit();
            } catch (Throwable e) { failure.compareAndSet(null, e); }
        };
        Thread a = new Thread(() -> run.accept(left), "dsc-distinct-a");
        Thread b = new Thread(() -> run.accept(right), "dsc-distinct-b");
        a.start(); b.start(); join(a); join(b);
        expect(failure.get() == null && left.amountOf(key) == 1 && right.amountOf(key) == 1
                && budget.committedBytes() == 2 * bytes, "distinct volume overlap failed: " + failure.get());
    }

    private static void exceptionReleasesVolume() {
        var key = ItemKey.of(net.minecraft.world.item.Items.STONE);
        var ledger = new VolumeLedger(() -> { }, 8);
        LedgerTransaction closed = LedgerTransaction.open();
        try (closed) {
            expect(ledger.insert(key, 1, closed) == 1, "exception fixture insert");
            throw new IllegalArgumentException("injected mutation failure");
        } catch (IllegalArgumentException expected) {
            expect("injected mutation failure".equals(expected.getMessage()), "unexpected exception");
        }
        closed.close();
        try (var tx = LedgerTransaction.open()) {
            expect(ledger.insert(key, 1, tx) == 1, "exception left volume occupied");
            tx.commit();
        }
        expect(ledger.amountOf(key) == 1, "exception rollback changed items");
    }

    private static void concurrentVolumeStress() {
        var key = ItemKey.of(net.minecraft.world.item.Items.STONE);
        var ledger = new VolumeLedger(() -> { }, 8);
        ledger.load(key, 1);
        ledger.attachAccounting(new GlobalBudget(0, ItemKeyCodec.write(key).sizeInBytes()), new TransactionRegistry());
        var failure = new AtomicReference<Throwable>();
        Thread[] workers = new Thread[4];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                try {
                    for (int accepted = 0, attempts = 0; accepted < 100;) {
                        if (++attempts > 200_000) throw new IllegalStateException("too many retries");
                        try (var tx = LedgerTransaction.open()) {
                            if (ledger.insert(key, 1, tx) != 0) { tx.commit(); accepted++; }
                        }
                        if ((attempts & 63) == 0) Thread.yield();
                    }
                } catch (Throwable e) { failure.compareAndSet(null, e); }
            }, "dsc-volume-stress-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) join(worker);
        expect(failure.get() == null && ledger.amountOf(key) == 401, "concurrent mutations lost items: " + failure.get());
    }

    private static void sameVolumeConflictAndRetry() {
        var stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
        long bytes = ItemKeyCodec.write(stone).sizeInBytes();
        var budget = new GlobalBudget(0, bytes);
        var ledger = new VolumeLedger(() -> { }, 8); ledger.load(stone, 1);
        ledger.attachAccounting(budget, new TransactionRegistry());
        var occupied = new CountDownLatch(1); var checked = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread first = new Thread(() -> {
            try (var tx = LedgerTransaction.open()) {
                expect(ledger.insert(stone, 1, tx) == 1, "first insert failed");
                try (var child = tx.openNested()) {
                    expect(ledger.insert(stone, 1, child) == 1, "nested reentry failed");
                }
                occupied.countDown(); expect(checked.await(3, TimeUnit.SECONDS), "conflict test timed out");
            } catch (Throwable e) { failure.compareAndSet(null, e); }
        }, "dsc-volume-owner-test");
        Thread second = new Thread(() -> {
            try {
                expect(occupied.await(3, TimeUnit.SECONDS), "owner did not start");
                try (var tx = LedgerTransaction.open()) {
                    expect(ledger.insert(stone, 1, tx) == 0 && ledger.extract(stone, 1, tx) == 0, "concurrent mutation was accepted");
                    expect(ledger.amountOf(stone) == 2 && budget.committedBytes() == bytes, "conflict changed data or budget");
                    tx.commit();
                }
            } catch (Throwable e) { failure.compareAndSet(null, e); }
            finally { checked.countDown(); }
        }, "dsc-volume-contender-test");
        first.start(); second.start(); join(first); join(second);
        expect(failure.get() == null && ledger.amountOf(stone) == 1, "rollback/concurrent conflict failed: " + failure.get());
        try (var tx = LedgerTransaction.open()) { expect(ledger.insert(stone, 1, tx) == 1, "retry after rollback failed"); tx.commit(); }
        expect(ledger.amountOf(stone) == 2 && budget.committedBytes() == bytes && ledger.mutationConflictsForTest() == 2, "retry or conflict accounting failed");
        try (var tx = LedgerTransaction.open()) { expect(ledger.insert(stone, 1, tx) == 1, "commit fixture"); tx.commit(); }
        try (var tx = LedgerTransaction.open()) { expect(ledger.insert(stone, 1, tx) == 1, "retry after commit failed"); tx.commit(); }
        expect(ledger.amountOf(stone) == 4, "retry after commit changed item count");
    }

    private static void realLedgerBudgetAndRootIsolation() {
        var stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
        long bytes = ItemKeyCodec.write(stone).sizeInBytes();
        var budgetA = new GlobalBudget(0, bytes); var budgetB = new GlobalBudget(0, bytes);
        var rootA = new TransactionRegistry(); var rootB = new TransactionRegistry();
        var a = new VolumeLedger(() -> { }, 8); var otherA = new VolumeLedger(() -> { }, 8);
        var b = new VolumeLedger(() -> { }, 8);
        a.load(stone, 6); a.attachAccounting(budgetA, rootA);
        otherA.attachAccounting(budgetA, rootA); b.attachAccounting(budgetB, rootB);
        try (var tx = LedgerTransaction.open()) {
            var held = a.reserveExtraction(stone, 6, tx);
            expect(otherA.insert(stone, 1, tx) == 0, "pending refund released root budget");
            held.settle(0); tx.commit();
        }
        expect(a.amountOf(stone) == 6 && budgetA.committedBytes() == bytes, "refund changed items or budget");
        try (var tx = LedgerTransaction.open()) {
            var first = a.reserveExtraction(stone, 6, tx);
            expect(a.insert(stone, 2, tx) == 2, "same identity could not reenter during reservation");
            var second = a.reserveExtraction(stone, 2, tx);
            first.settle(6); second.settle(0); tx.commit();
        }
        expect(a.amountOf(stone) == 2 && budgetA.committedBytes() == bytes, "overlapping refund lost budget or items");
        try (var tx = LedgerTransaction.open()) { expect(a.insert(stone, 4, tx) == 4, "restore test fixture"); tx.commit(); }
        try (var tx = LedgerTransaction.open()) {
            expect(a.extract(stone, 6, tx) == 6 && otherA.insert(stone, 6, tx) == 6, "same root transfer rejected");
            expect(b.insert(stone, 1, tx) == 1, "independent root budget rejected");
            try (var capture = rootA.tryCapture(10, TimeUnit.MILLISECONDS)) { expect(capture == null, "root A captured active transaction"); }
            try (var capture = rootB.tryCapture(10, TimeUnit.MILLISECONDS)) { expect(capture == null, "root B captured active transaction"); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        expect(a.amountOf(stone) == 6 && b.amountOf(stone) == 0 && budgetA.committedBytes() == bytes && budgetB.committedBytes() == 0, "cross-root rollback mismatch");
        try (var tx = LedgerTransaction.open()) {
            expect(a.extract(stone, 1, tx) == 1, "root A mutation failed");
            try (var capture = rootB.tryCapture(1, TimeUnit.SECONDS)) { expect(capture != null, "root A blocked root B"); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        try (var tx = LedgerTransaction.open()) { a.reserveExtraction(stone, 6, tx); tx.commit(); }
        expect(a.totalVariantNbtBytes() == 0 && budgetA.committedBytes() == 0, "unsettled committed withdrawal retained budget");
        try (var tx = LedgerTransaction.open()) {
            expect(a.insert(stone, 4, tx) == 4, "unsettled new entry fixture");
            a.reserveExtraction(stone, 4, tx); tx.commit();
        }
        expect(a.totalVariantNbtBytes() == 0 && budgetA.committedBytes() == 0, "new entry then unsettled withdrawal retained budget");
        try (var tx = LedgerTransaction.open()) {
            expect(a.insert(stone, 3, tx) == 3, "nested fixture");
            try (var child = tx.openNested()) { a.reserveExtraction(stone, 3, child); child.commit(); }
        }
        expect(a.totalVariantNbtBytes() == 0 && budgetA.committedBytes() == 0, "nested unsettled rollback retained budget");
    }

    private static void nestedDeltas() {
        var budget = new GlobalBudget(186, 186);
        try (var outer = LedgerTransaction.open()) {
            expect(!budget.tryReserve(1, outer), "over limit accepted");
            try (var child = outer.openNested()) { budget.release(186, child); child.commit(); }
            expect(budget.tryReserve(186, outer), "child delta was not merged");
            outer.commit();
        }
        expect(budget.committedBytes() == 186, "merged budget differs");
        try (var outer = LedgerTransaction.open()) {
            try (var child = outer.openNested()) { budget.release(186, child); }
            expect(!budget.tryReserve(1, outer), "aborted child released budget");
        }
        expect(budget.committedBytes() == 186, "rollback changed committed budget");
        try (var outer = LedgerTransaction.open()) {
            try (var child = outer.openNested()) { budget.release(186, child); child.commit(); }
        }
        expect(budget.committedBytes() == 186, "outer rollback changed committed budget");
    }

    private static void concurrentTransactions() {
        var budget = new GlobalBudget(0, 372);
        var opened = new CountDownLatch(1); var committed = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread a = new Thread(() -> {
            try (var tx = LedgerTransaction.open()) {
                expect(budget.tryReserve(186, tx), "first reservation"); opened.countDown();
                expect(committed.await(3, TimeUnit.SECONDS), "second transaction timeout");
            } catch (Throwable e) { failure.compareAndSet(null, e); }
        }, "dsc-budget-rollback-test");
        Thread b = new Thread(() -> {
            try {
                expect(opened.await(3, TimeUnit.SECONDS), "first transaction timeout");
                try (var tx = LedgerTransaction.open()) { expect(budget.tryReserve(186, tx), "second reservation"); tx.commit(); }
            } catch (Throwable e) { failure.compareAndSet(null, e); }
            finally { committed.countDown(); }
        }, "dsc-budget-commit-test");
        a.start(); b.start(); join(a); join(b);
        expect(failure.get() == null && budget.committedBytes() == 186, "rollback erased concurrent commit: " + failure.get());
        var limit = new GlobalBudget(0, 186);
        try (var tx = LedgerTransaction.open()) {
            expect(limit.tryReserve(186, tx), "limit reservation");
            Thread other = new Thread(() -> {
                try (var concurrent = LedgerTransaction.open()) { expect(!limit.tryReserve(186, concurrent), "concurrent reservation exceeded limit"); }
                catch (Throwable e) { failure.compareAndSet(null, e); }
            }, "dsc-budget-limit-test");
            other.start(); join(other);
        }
        expect(failure.get() == null, "concurrent limit failure: " + failure.get());
    }

    private static void captureGate() {
        var registry = new TransactionRegistry();
        var opened = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try (var outer = LedgerTransaction.open()) {
                outer.enter(registry);
                try (var child = outer.openNested()) { child.commit(); }
                opened.countDown(); expect(release.await(3, TimeUnit.SECONDS), "capture test release timeout");
            } catch (Throwable e) { failure.set(e); }
        }, "dsc-capture-test");
        other.start();
        try {
            expect(opened.await(3, TimeUnit.SECONDS), "capture test open timeout");
            try (var capture = registry.tryCapture(20, TimeUnit.MILLISECONDS)) { expect(capture == null, "captured open transaction"); }
            release.countDown(); join(other);
            try (var capture = registry.tryCapture(1, TimeUnit.SECONDS)) { expect(capture != null, "capture did not recover"); }
            expect(failure.get() == null, "capture worker failure: " + failure.get());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        finally { release.countDown(); }
    }

    private static void captureBlocksNewEntries() {
        var registry = new TransactionRegistry();
        var attempted = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            attempted.countDown();
            try (var tx = LedgerTransaction.open()) { tx.enter(registry); entered.countDown(); }
            catch (Throwable e) { failure.set(e); }
        }, "dsc-gate-entry-test");
        try (var capture = registry.tryCapture(1, TimeUnit.SECONDS)) {
            expect(capture != null, "capture gate unavailable");
            other.start(); expect(attempted.await(1, TimeUnit.SECONDS), "entry test did not start");
            expect(!entered.await(30, TimeUnit.MILLISECONDS), "entry crossed active capture");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        join(other);
        expect(entered.getCount() == 0 && failure.get() == null, "entry did not resume after capture");
    }

    private static void join(Thread thread) {
        try { thread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        expect(!thread.isAlive(), "test thread did not terminate");
    }
    private static void expect(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
