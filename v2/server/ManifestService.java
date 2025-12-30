import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

class ManifestService {

    private final Path dataRoot;
    private final Path manifestRoot;

    private final ExecutorService builderPool =
            Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors());

    private final Set<String> building =
            ConcurrentHashMap.newKeySet();

    ManifestService(Path dataRoot, Path manifestRoot) {
        this.dataRoot = dataRoot;
        this.manifestRoot = manifestRoot;
    }

    // GET /api/folders
    void handleFolders(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "Method Not Allowed");
            return;
        }

        List<String> folders = new ArrayList<>();
        try (DirectoryStream<Path> ds =
                     Files.newDirectoryStream(dataRoot)) {

            for (Path p : ds)
                if (Files.isDirectory(p))
                    folders.add(p.getFileName().toString());
        }

        sendJson(ex, 200, toJsonArray(folders));
    }

    // GET /api/manifest/{folder}
    void handleManifest(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "Method Not Allowed");
            return;
        }

        String folder = extractFolder(ex.getRequestURI().getPath());
        if (folder == null) {
            send(ex, 400, "Bad Request");
            return;
        }

        Path dataDir = dataRoot.resolve(folder);
        Path manifest = manifestRoot.resolve(folder + ".json");

        if (!Files.isDirectory(dataDir)) {
            send(ex, 404, "Not Found");
            return;
        }

        if (Files.exists(manifest)) {
            byte[] out = Files.readAllBytes(manifest);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("Cache-Control", "max-age=60");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
            return;
        }

        if (building.add(folder)) {
            builderPool.submit(() -> {
                try {
                    ManifestBuilder.buildAtomic(
                            dataDir, manifest, folder);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    building.remove(folder);
                }
            });
        }

        sendJson(ex, 202, "{\"status\":\"building\"}");
    }

    // ---------- helpers ----------

    private static String extractFolder(String path) {
        // /api/manifest/{folder}
        String prefix = "/api/manifest/";
        if (!path.startsWith(prefix)) return null;

        String rest = path.substring(prefix.length());
        if (rest.isEmpty() || rest.contains("/"))
            return null;

        return rest;
    }

    private static void send(HttpExchange ex, int code, String msg)
            throws IOException {
        byte[] out = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static void sendJson(HttpExchange ex, int code, byte[] out)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static byte[] toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(items.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
