package io.github.ibcmanager.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class FilePermissionHardener {
    private FilePermissionHardener() { }

    public static void hardenFile(Path file) throws IOException {
        if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view != null) {
            UserPrincipal owner = Files.getOwner(file);
            Set<AclEntryPermission> permissions = EnumSet.of(
                    AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                    AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_NAMED_ATTRS,
                    AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.READ_ACL,
                    AclEntryPermission.WRITE_ACL, AclEntryPermission.SYNCHRONIZE,
                    AclEntryPermission.DELETE);
            view.setAcl(List.of(allow(owner, permissions, Set.of())));
        }
    }

    public static void hardenDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (Files.getFileAttributeView(directory, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (view != null) {
            UserPrincipal owner = Files.getOwner(directory);
            Set<AclEntryPermission> permissions = ownerDirectoryPermissions();
            Set<AclEntryFlag> flags = EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            view.setAcl(List.of(allow(owner, permissions, flags)));
        }
    }

    static Set<AclEntryPermission> ownerDirectoryPermissions() {
        return EnumSet.of(
                AclEntryPermission.LIST_DIRECTORY, AclEntryPermission.ADD_FILE,
                AclEntryPermission.ADD_SUBDIRECTORY, AclEntryPermission.READ_DATA,
                AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                AclEntryPermission.EXECUTE, AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_ACL, AclEntryPermission.DELETE_CHILD,
                AclEntryPermission.DELETE, AclEntryPermission.SYNCHRONIZE);
    }

    private static AclEntry allow(UserPrincipal owner, Set<AclEntryPermission> permissions,
            Set<AclEntryFlag> flags) {
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(permissions);
        if (!flags.isEmpty()) builder.setFlags(flags);
        return builder.build();
    }
}
