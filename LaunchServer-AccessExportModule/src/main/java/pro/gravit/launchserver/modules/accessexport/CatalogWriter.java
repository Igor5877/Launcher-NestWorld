package pro.gravit.launchserver.modules.accessexport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes the profile catalog into the tables of the Azuriom plugin (launcher_access_profiles and launcher_access_mods).
 */
public final class CatalogWriter {
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");

    private final String profilesTable;
    private final String modsTable;

    public CatalogWriter(String tablePrefix) {
        String prefix = tablePrefix == null ? "" : tablePrefix;
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid table prefix: " + prefix);
        }
        this.profilesTable = prefix + "launcher_access_profiles";
        this.modsTable = prefix + "launcher_access_mods";
    }

    /**
     * Idempotent: the catalog ends up equal to {@code profiles}. Profiles missing from the list leave the catalog
     * (the rules the admin made for them stay in the plugin). An empty list changes nothing: it means the profiles
     * were not loaded, not that all of them were deleted.
     *
     * @return false if nothing was written
     */
    public boolean write(Connection connection, List<CatalogRows.Profile> profiles) throws SQLException {
        if (profiles.isEmpty()) {
            return false;
        }

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            Set<String> uuids = new HashSet<>();

            for (CatalogRows.Profile profile : profiles) {
                uuids.add(profile.uuid());
                writeProfile(connection, profile, now);
                writeMods(connection, profile, now);
            }

            deleteMissingProfiles(connection, uuids);
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void writeProfile(Connection c, CatalogRows.Profile p, Timestamp now) throws SQLException {
        int updated;
        try (PreparedStatement s = c.prepareStatement("UPDATE " + profilesTable
                + " SET title = ?, version = ?, limited = ?, sort_index = ?, synced_at = ?, updated_at = ? WHERE uuid = ?")) {
            s.setString(1, p.title());
            s.setString(2, p.version());
            s.setBoolean(3, p.limited());
            s.setInt(4, p.sortIndex());
            s.setTimestamp(5, now);
            s.setTimestamp(6, now);
            s.setString(7, p.uuid());
            updated = s.executeUpdate();
        }

        if (updated == 0) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO " + profilesTable
                    + " (uuid, title, version, limited, sort_index, synced_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                s.setString(1, p.uuid());
                s.setString(2, p.title());
                s.setString(3, p.version());
                s.setBoolean(4, p.limited());
                s.setInt(5, p.sortIndex());
                s.setTimestamp(6, now);
                s.setTimestamp(7, now);
                s.setTimestamp(8, now);
                s.executeUpdate();
            }
        }
    }

    private void writeMods(Connection c, CatalogRows.Profile p, Timestamp now) throws SQLException {
        Set<String> keys = new HashSet<>();

        for (CatalogRows.Mod mod : p.mods()) {
            keys.add(mod.key());

            int updated;
            try (PreparedStatement s = c.prepareStatement("UPDATE " + modsTable
                    + " SET name = ?, category = ?, info = ?, limited = ?, visible = ?, updated_at = ? WHERE profile_uuid = ? AND mod_key = ?")) {
                s.setString(1, mod.name());
                s.setString(2, mod.category());
                s.setString(3, mod.info());
                s.setBoolean(4, mod.limited());
                s.setBoolean(5, mod.visible());
                s.setTimestamp(6, now);
                s.setString(7, p.uuid());
                s.setString(8, mod.key());
                updated = s.executeUpdate();
            }

            if (updated == 0) {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO " + modsTable
                        + " (profile_uuid, mod_key, name, category, info, limited, visible, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    s.setString(1, p.uuid());
                    s.setString(2, mod.key());
                    s.setString(3, mod.name());
                    s.setString(4, mod.category());
                    s.setString(5, mod.info());
                    s.setBoolean(6, mod.limited());
                    s.setBoolean(7, mod.visible());
                    s.setTimestamp(8, now);
                    s.setTimestamp(9, now);
                    s.executeUpdate();
                }
            }
        }

        // mods removed from the profile
        Set<String> existing = new HashSet<>();
        try (PreparedStatement s = c.prepareStatement("SELECT mod_key FROM " + modsTable + " WHERE profile_uuid = ?")) {
            s.setString(1, p.uuid());
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    existing.add(rs.getString(1));
                }
            }
        }
        existing.removeAll(keys);

        for (String key : existing) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM " + modsTable + " WHERE profile_uuid = ? AND mod_key = ?")) {
                s.setString(1, p.uuid());
                s.setString(2, key);
                s.executeUpdate();
            }
        }
    }

    private void deleteMissingProfiles(Connection c, Set<String> keep) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (PreparedStatement s = c.prepareStatement("SELECT uuid FROM " + profilesTable); var rs = s.executeQuery()) {
            while (rs.next()) {
                existing.add(rs.getString(1));
            }
        }
        existing.removeAll(keep);

        for (String uuid : existing) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM " + modsTable + " WHERE profile_uuid = ?")) {
                s.setString(1, uuid);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM " + profilesTable + " WHERE uuid = ?")) {
                s.setString(1, uuid);
                s.executeUpdate();
            }
        }
    }
}
