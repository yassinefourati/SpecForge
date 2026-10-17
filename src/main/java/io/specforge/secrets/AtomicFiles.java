package io.specforge.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/** Every write under ~/.specforge is atomic: temp file in the same folder, then a move (NFR-7). */
public final class AtomicFiles {

    private AtomicFiles() {}

    public static void write(Path file, String content) throws IOException {
        write(file, content.getBytes(StandardCharsets.UTF_8), false);
    }

    /** Writes and, when {@code ownerOnly}, restricts the file to its owner before content is visible at its final name. */
    public static void write(Path file, byte[] content, boolean ownerOnly) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
        try {
            if (ownerOnly) ownerOnly(tmp);
            Files.write(tmp, content);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Best effort: rw------- on POSIX, an owner-only ACL on Windows. */
    public static void ownerOnly(Path file) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) return;
        UserPrincipal owner = Files.getOwner(file);
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(entry));
    }
}
