package dev.kehai.digitalstorage.forge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** Forced temporary NBT followed by replacement; interrupted .tmp files remain evidence. */
final class ForgeTransferFiles {
    private ForgeTransferFiles() { }
    static void write(Path destination, CompoundTag tag) {
        Path temporary = destination.resolveSibling(destination.getFileName().toString().replace(".dat", ".tmp"));
        try {
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                var data = ByteBuffer.wrap(bytes.toByteArray());
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) { throw new IllegalStateException("Could not persist Forge transfer record", failure); }
    }
}
