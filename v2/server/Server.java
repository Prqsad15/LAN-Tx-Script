import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;

public class Server {

    static final Path DATA_ROOT = Paths.get("data");
    static final Path MANIFEST_ROOT = Paths.get("manifests");

    public static void main(String[] args) throws Exception {

        Files.createDirectories(MANIFEST_ROOT);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(8080), 0);

        ManifestService manifestService =
                new ManifestService(DATA_ROOT, MANIFEST_ROOT);

        server.createContext("/api/folders",
                manifestService::handleFolders);

        server.createContext("/api/manifest",
                manifestService::handleManifest);

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Backend running on :8080");
    }
}
