package pro.gravit.launchserver.modules.accessexport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.ClientProfileBuilder;
import pro.gravit.launcher.base.profiles.optional.OptionalFile;

import java.security.Security;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CatalogRowsTest {
    private static OptionalFile optional(String name, boolean limited) {
        OptionalFile file = new OptionalFile();
        file.name = name;
        file.limited = limited;
        file.visible = true;
        return file;
    }

    @Test
    public void exportsOnlyWhatThePluginNeeds() {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());

        Set<OptionalFile> optionals = new LinkedHashSet<>();
        optionals.add(optional("Sodium", true));
        optionals.add(optional("Bad*Name", true));
        optionals.add(optional("JEI", false));

        ClientProfileBuilder builder = new ClientProfileBuilder();
        builder.setTitle("Sky");
        builder.setUuid(UUID.fromString("00000000-0000-0000-0000-0000000000A1"));
        builder.setLimited(true);
        builder.setUpdateOptional(optionals);
        ClientProfile profile = builder.createClientProfile();

        List<String> problems = new ArrayList<>();
        var rows = CatalogRows.from(Set.of(profile), problems);

        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("00000000-0000-0000-0000-0000000000a1", rows.get(0).uuid(), "lower case, with dashes");
        Assertions.assertEquals("Sky", rows.get(0).title());
        Assertions.assertTrue(rows.get(0).limited());
        Assertions.assertEquals(List.of("jei", "sodium"), rows.get(0).mods().stream().map(CatalogRows.Mod::key).toList());
        Assertions.assertEquals(1, problems.size(), "the wildcard name is reported");
    }
}
