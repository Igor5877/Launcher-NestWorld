package pro.gravit.launchserver.modules.accessexport;

import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.optional.OptionalFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the Azuriom plugin needs to know about the profiles: no JVM arguments, paths or server addresses.
 */
public final class CatalogRows {
    public static final int MAX_MOD_KEY = 100;

    private CatalogRows() {
    }

    public record Mod(String key, String name, String category, String info, boolean limited, boolean visible) {
    }

    public record Profile(String uuid, String title, String version, boolean limited, int sortIndex, List<Mod> mods) {
    }

    public static List<Profile> from(Set<ClientProfile> profiles, List<String> problems) {
        List<Profile> result = new ArrayList<>();

        for (ClientProfile profile : profiles) {
            // the same lower case key the launcher builds the permission from (TriggerManager)
            TreeMap<String, Mod> mods = new TreeMap<>();

            if (profile.getOptional() != null) {
                for (OptionalFile file : profile.getOptional()) {
                    String key = modKey(file.name);

                    if (key == null) {
                        problems.add("profile " + profile.getTitle() + ": mod '" + file.name + "' cannot be used in a permission, skipped");
                        continue;
                    }

                    // two mods with the same name: the stricter flag wins
                    Mod old = mods.get(key);
                    boolean limited = file.limited || (old != null && old.limited());
                    mods.put(key, new Mod(key, file.name, cut(file.category, 100), cut(file.info, 1000), limited, file.visible));
                }
            }

            result.add(new Profile(
                    profile.getUUID().toString().toLowerCase(Locale.ROOT),
                    cut(profile.getTitle(), 255),
                    profile.getVersion() == null ? null : cut(profile.getVersion().toString(), 64),
                    profile.isLimited(),
                    profile.getSortIndex(),
                    new ArrayList<>(mods.values())));
        }

        result.sort(Comparator.comparing(Profile::uuid));
        return result;
    }

    /**
     * Null if the name cannot be part of a permission: empty, too long, a wildcard or a control character.
     */
    public static String modKey(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '*' || c < 0x20 || c == 0x7f) {
                return null;
            }
        }

        String key = name.toLowerCase(Locale.ROOT);
        return key.length() > MAX_MOD_KEY ? null : key;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
