package pro.gravit.launchserver.modules.accessexport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The tables are the ones the Azuriom plugin migration creates (see AzuriomPlugin-LauncherAccess).
 */
public class CatalogWriterTest {
    private static final String P1 = "00000000-0000-0000-0000-0000000000a1";
    private static final String P2 = "00000000-0000-0000-0000-0000000000a2";

    private Connection connection;

    @BeforeEach
    public void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:catalog" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE az_launcher_access_profiles (id BIGINT AUTO_INCREMENT PRIMARY KEY, uuid CHAR(36) NOT NULL UNIQUE, "
                    + "title VARCHAR(255) NOT NULL, version VARCHAR(64), limited BOOLEAN NOT NULL DEFAULT FALSE, sort_index INT NOT NULL DEFAULT 0, "
                    + "synced_at TIMESTAMP NULL, created_at TIMESTAMP NULL, updated_at TIMESTAMP NULL)");
            s.execute("CREATE TABLE az_launcher_access_mods (id BIGINT AUTO_INCREMENT PRIMARY KEY, profile_uuid CHAR(36) NOT NULL, "
                    + "mod_key VARCHAR(100) NOT NULL, name VARCHAR(255) NOT NULL, category VARCHAR(100), info VARCHAR(1000), "
                    + "limited BOOLEAN NOT NULL DEFAULT FALSE, visible BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP NULL, updated_at TIMESTAMP NULL, "
                    + "UNIQUE (profile_uuid, mod_key))");
        }
    }

    @AfterEach
    public void tearDown() throws SQLException {
        connection.close();
    }

    private static CatalogRows.Profile profile(String uuid, String title, boolean limited, CatalogRows.Mod... mods) {
        return new CatalogRows.Profile(uuid, title, "1.20.1", limited, 0, new ArrayList<>(List.of(mods)));
    }

    private static CatalogRows.Mod mod(String name, boolean limited) {
        return new CatalogRows.Mod(CatalogRows.modKey(name), name, null, null, limited, true);
    }

    private List<String> column(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    @Test
    public void writesProfilesAndMods() throws SQLException {
        var writer = new CatalogWriter("az_");
        Assertions.assertTrue(writer.write(connection, List.of(
                profile(P1, "Sky", true, mod("Sodium", true), mod("JEI", false)),
                profile(P2, "Magic", false))));

        Assertions.assertEquals(List.of("Magic", "Sky"), column("SELECT title FROM az_launcher_access_profiles ORDER BY title"));
        Assertions.assertEquals(List.of("jei", "sodium"), column("SELECT mod_key FROM az_launcher_access_mods ORDER BY mod_key"));
        Assertions.assertEquals(List.of("TRUE"), column("SELECT CAST(limited AS VARCHAR) FROM az_launcher_access_mods WHERE mod_key = 'sodium'"));
    }

    @Test
    public void isIdempotentAndUpdatesInPlace() throws SQLException {
        var writer = new CatalogWriter("az_");
        writer.write(connection, List.of(profile(P1, "Sky", true, mod("Sodium", true))));
        var firstId = column("SELECT id FROM az_launcher_access_profiles");

        writer.write(connection, List.of(profile(P1, "Sky v2", false, mod("Sodium", false))));

        Assertions.assertEquals(firstId, column("SELECT id FROM az_launcher_access_profiles"), "the row is updated, not recreated");
        Assertions.assertEquals(List.of("Sky v2"), column("SELECT title FROM az_launcher_access_profiles"));
        Assertions.assertEquals(List.of("FALSE"), column("SELECT CAST(limited AS VARCHAR) FROM az_launcher_access_mods"));
        Assertions.assertEquals(1, column("SELECT id FROM az_launcher_access_mods").size());
    }

    @Test
    public void removedModsAndProfilesLeaveTheCatalog() throws SQLException {
        var writer = new CatalogWriter("az_");
        writer.write(connection, List.of(
                profile(P1, "Sky", true, mod("Sodium", true), mod("JEI", false)),
                profile(P2, "Magic", true, mod("Botania", true))));

        writer.write(connection, List.of(profile(P1, "Sky", true, mod("Sodium", true))));

        Assertions.assertEquals(List.of("Sky"), column("SELECT title FROM az_launcher_access_profiles"));
        Assertions.assertEquals(List.of("sodium"), column("SELECT mod_key FROM az_launcher_access_mods"));
    }

    @Test
    public void emptyListMeansProfilesWereNotLoadedAndChangesNothing() throws SQLException {
        var writer = new CatalogWriter("az_");
        writer.write(connection, List.of(profile(P1, "Sky", true, mod("Sodium", true))));

        Assertions.assertFalse(writer.write(connection, List.of()));

        Assertions.assertEquals(List.of("Sky"), column("SELECT title FROM az_launcher_access_profiles"));
        Assertions.assertEquals(List.of("sodium"), column("SELECT mod_key FROM az_launcher_access_mods"));
    }

    @Test
    public void failureRollsEverythingBack() throws SQLException {
        var writer = new CatalogWriter("az_");
        writer.write(connection, List.of(profile(P1, "Sky", true, mod("Sodium", true))));

        // the second profile has a title that does not fit the column: the whole export must not be half applied
        Assertions.assertThrows(SQLException.class, () -> writer.write(connection, List.of(
                profile(P1, "Renamed", true, mod("Sodium", true)),
                profile(P2, "x".repeat(300), true))));

        Assertions.assertEquals(List.of("Sky"), column("SELECT title FROM az_launcher_access_profiles"));
        Assertions.assertTrue(connection.getAutoCommit(), "auto commit is restored");
    }

    @Test
    public void tablePrefixIsValidated() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new CatalogWriter("az_; DROP TABLE users"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new CatalogWriter("a b"));
        Assertions.assertDoesNotThrow(() -> new CatalogWriter(""));
        Assertions.assertDoesNotThrow(() -> new CatalogWriter(null));
    }

    @Test
    public void modKeyMatchesTheLauncher() {
        Assertions.assertEquals("sodium", CatalogRows.modKey("Sodium"));
        Assertions.assertEquals("ender io", CatalogRows.modKey("Ender IO"));
        Assertions.assertNull(CatalogRows.modKey("a*b"));
        Assertions.assertNull(CatalogRows.modKey("a\nb"));
        Assertions.assertNull(CatalogRows.modKey(""));
        Assertions.assertNull(CatalogRows.modKey(null));
        Assertions.assertNull(CatalogRows.modKey("x".repeat(101)));
        Assertions.assertEquals("x".repeat(100), CatalogRows.modKey("X".repeat(100)));
    }
}
