package me.geraldr12.v110;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public interface MigrationFileOps {

    void copy(Path source, Path target) throws IOException;

    void rename(Path source, Path target) throws IOException;

    void writeAtomically(Path target, byte[] content) throws IOException;

    MigrationFileOps DEFAULT = new MigrationFileOps() {
        @Override
        public void copy(Path source, Path target) throws IOException {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void rename(Path source, Path target) throws IOException {
            if (Files.exists(target)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            Files.move(source, target);
        }

        @Override
        public void writeAtomically(Path target, byte[] content) throws IOException {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.write(temporary, content);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    };
}
