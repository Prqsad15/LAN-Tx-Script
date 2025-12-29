import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class HttpRecursiveDownloader {

    static final String BASE_URL = "http://10.1.1.8:8000/";
    static final Path DEST = Paths.get("Backup");
    static final int THREADS = 32;

    static HttpClient client = HttpClient.newBuilder()
            .executor(Executors.newFixedThreadPool(THREADS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static Pattern linkPattern = Pattern.compile("href=\"([^\"]+)\"");

    static List<String> files = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        crawl(BASE_URL);
        System.out.println("Found " + files.size() + " files");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (String url : files) {
            pool.submit(() -> download(url));
        }

        pool.shutdown();
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        System.out.println("Done");
    }

    static void crawl(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        String body = client.send(req, HttpResponse.BodyHandlers.ofString()).body();

        Matcher m = linkPattern.matcher(body);
        while (m.find()) {
            String href = m.group(1);
            if (href.equals("../")) continue;

            String full = url + href;
            if (href.endsWith("/")) {
                crawl(full);
            } else {
                files.add(full);
            }
        }
    }

    static void download(String url) {
        try {
            String rel = url.substring(BASE_URL.length());
            Path out = DEST.resolve(rel);

            Files.createDirectories(out.getParent());

            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            client.send(req, HttpResponse.BodyHandlers.ofFile(out));

        } catch (Exception e) {
            System.err.println("FAIL: " + url + " -> " + e);
        }
    }
}
