package pro.gravit.launchserver.modules.accessexport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launcher.base.modules.LauncherInitContext;
import pro.gravit.launcher.base.modules.LauncherModule;
import pro.gravit.launcher.base.modules.LauncherModuleInfo;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.auth.AuthProviderPair;
import pro.gravit.launchserver.auth.core.AzuriomCoreProvider;
import pro.gravit.launchserver.modules.events.LaunchServerFullInitEvent;
import pro.gravit.utils.Version;
import pro.gravit.utils.command.Command;
import pro.gravit.utils.command.CommandHandler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Tells the Azuriom plugin "Launcher Access" which profiles and optional mods LaunchServer has, so the admin can
 * pick them from a list instead of typing UUIDs. Only the names, flags and ids are exported.
 */
public class AccessExportModule extends LauncherModule {
    private static final String MODULE_NAME = "AccessExportModule";
    private static final Version MODULE_VERSION = new Version(1, 0, 0);
    /**
     * Write again at least this often even if nothing changed (the tables may have been recreated)
     */
    private static final long FORCE_AFTER_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final Logger logger = LogManager.getLogger();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private AccessExportConfig config;
    private LaunchServer server;
    private CatalogWriter writer;
    private List<CatalogRows.Profile> lastExported;
    private long lastExportAt;
    private boolean noDatabaseReported;
    private String lastProblem;

    public AccessExportModule() {
        super(new LauncherModuleInfo(MODULE_NAME, MODULE_VERSION));
    }

    @Override
    public void init(LauncherInitContext initContext) {
        registerEvent(this::finishInit, LaunchServerFullInitEvent.class);
    }

    private void finishInit(LaunchServerFullInitEvent event) {
        this.server = event.server;
        loadConfig();

        if (!config.enabled) {
            logger.info("AccessExportModule is disabled");
            return;
        }

        try {
            writer = new CatalogWriter(config.tablePrefix);
        } catch (IllegalArgumentException e) {
            logger.error("AccessExportModule: {}", e.getMessage());
            return;
        }

        registerCommand("exportprofiles", new Command() {
            @Override
            public String getArgsDescription() {
                return null;
            }

            @Override
            public String getUsageDescription() {
                return "Write the profiles and mods into the Azuriom \"Launcher Access\" plugin tables";
            }

            @Override
            public void invoke(String... args) {
                export(true);
            }
        });

        int interval = Math.max(10, config.intervalSeconds);
        server.service.scheduleWithFixedDelay(() -> {
            try {
                export(false);
            } catch (Throwable e) {
                logger.error("AccessExportModule: export failed", e);
            }
        }, 15, interval, TimeUnit.SECONDS);

        logger.info("AccessExportModule started (every {} s)", interval);
    }

    /**
     * The type of LaunchServer.commandHandler differs between LaunchServer builds, so it is read by name:
     * the module then works with both.
     */
    private void registerCommand(String name, Command command) {
        try {
            Object handler = LaunchServer.class.getField("commandHandler").get(server);
            ((CommandHandler) handler).registerCommand(name, command);
        } catch (ReflectiveOperationException | ClassCastException e) {
            logger.warn("AccessExportModule: cannot register the command '{}' ({}), the export still runs on schedule", name, e.toString());
        }
    }

    private synchronized void export(boolean force) {
        AzuriomCoreProvider provider = findProvider();

        if (provider == null) {
            if (!noDatabaseReported) {
                noDatabaseReported = true;
                logger.error("AccessExportModule: no Azuriom auth provider with an \"sql\" section found, nothing to export to");
            }
            return;
        }

        List<String> problems = new ArrayList<>();
        List<CatalogRows.Profile> rows = CatalogRows.from(server.getProfiles(), problems);

        long now = System.currentTimeMillis();
        if (!force && Objects.equals(rows, lastExported) && now - lastExportAt < FORCE_AFTER_MILLIS) {
            return;
        }

        if (rows.isEmpty()) {
            logger.warn("AccessExportModule: LaunchServer has no profiles loaded, the catalog is left as it is");
            return;
        }

        try (Connection connection = provider.sql.getSQLConfig().getConnection()) {
            if (writer.write(connection, rows)) {
                lastExported = rows;
                lastExportAt = now;
                lastProblem = null;
                int mods = rows.stream().mapToInt(p -> p.mods().size()).sum();
                logger.info("AccessExportModule: exported {} profiles and {} mods", rows.size(), mods);
                problems.forEach(logger::warn);
            }
        } catch (Exception e) {
            // typically: the plugin is not installed yet (tables missing). Tell once per distinct problem, then retry quietly
            String problem = String.valueOf(e.getMessage());
            if (!problem.equals(lastProblem)) {
                lastProblem = problem;
                logger.error("AccessExportModule: cannot write the catalog ({}). Is the Azuriom plugin \"Launcher Access\" installed and table_prefix correct? Retrying every {} s", problem, Math.max(10, config.intervalSeconds));
            } else {
                logger.debug("AccessExportModule: still cannot write the catalog: {}", problem);
            }
        }
    }

    private AzuriomCoreProvider findProvider() {
        for (var entry : server.config.auth.entrySet()) {
            AuthProviderPair pair = entry.getValue();

            if (!config.authId.isEmpty() && !config.authId.equals(entry.getKey())) {
                continue;
            }

            if (pair.core instanceof AzuriomCoreProvider azuriom && azuriom.sql != null) {
                return azuriom;
            }
        }
        return null;
    }

    private void loadConfig() {
        Path configPath = modulesConfigManager.getModuleConfig(MODULE_NAME);
        try {
            if (!Files.exists(configPath)) {
                config = new AccessExportConfig();
                if (configPath.getParent() != null) Files.createDirectories(configPath.getParent());
                try (BufferedWriter out = Files.newBufferedWriter(configPath)) {
                    gson.toJson(config, out);
                }
            } else {
                try (BufferedReader in = Files.newBufferedReader(configPath)) {
                    config = gson.fromJson(in, AccessExportConfig.class);
                }
                if (config == null) {
                    config = new AccessExportConfig();
                }
            }
        } catch (IOException e) {
            logger.error("AccessExportModule: cannot read the config", e);
            config = new AccessExportConfig();
        }

        if (config.authId == null) config.authId = "";
        if (config.tablePrefix == null) config.tablePrefix = "";
    }
}
