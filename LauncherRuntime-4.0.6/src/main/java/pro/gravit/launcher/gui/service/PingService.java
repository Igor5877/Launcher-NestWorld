package pro.gravit.launcher.gui.service;

import pro.gravit.launcher.runtime.client.ServerPinger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PingService {
    private final Map<String, CompletableFuture<PingServerReport>> reports = new ConcurrentHashMap<>();

    public CompletableFuture<PingServerReport> getPingReport(String serverName) {
        CompletableFuture<PingServerReport> report = reports.computeIfAbsent(serverName,
                                                                             k -> new CompletableFuture<>());
        return report;
    }

    public void addReports(Map<String, PingServerReport> map) {
        map.forEach((k, v) -> {
            CompletableFuture<PingServerReport> report = getPingReport(k);
            report.complete(v);
        });
    }

    public void addReport(String name, ServerPinger.Result result, long pingMs) {
        CompletableFuture<PingServerReport> report = getPingReport(name);
        PingServerReport value = new PingServerReport(name, result.maxPlayers, result.onlinePlayers, pingMs, null);
        report.complete(value);
    }

    public void clear() {
        reports.forEach((k, v) -> {
            if (!v.isDone()) {
                v.completeExceptionally(new InterruptedException());
            }
        });
        reports.clear();
    }

    public static class PingServerReport {
        public final String name;
        public final int maxPlayers;
        public final int playersOnline;
        /** Час відгуку ping-запиту, мс. -1, якщо невідомо. */
        public final long pingMs;
        /** TPS сервера. Наразі протокол пінгу цього не повертає, тому завжди null,
         *  поле лишене на майбутнє (якщо сервер колись почне віддавати цю метрику). */
        public final Double tps;

        public PingServerReport(String name, int maxPlayers, int playersOnline, long pingMs, Double tps) {
            this.name = name;
            this.maxPlayers = maxPlayers;
            this.playersOnline = playersOnline;
            this.pingMs = pingMs;
            this.tps = tps;
        }
    }
}
