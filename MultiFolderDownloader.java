import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MultiFolderDownloader {

    static final int THREADS = 32;
    static final int MAX_IN_FLIGHT = 128;

    static HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static Pattern linkPattern = Pattern.compile("href=\"([^\"]+)\"");

    static class Job {
        String baseUrl;
        Path dest;
        Job(String b, String d) {
            baseUrl = b.endsWith("/") ? b : b + "/";
            dest = Paths.get(d);
        }
    }

    static List<Job> jobs = List.of(
        new Job("http://10.1.1.8:8000/Camera/", "Camera"),
        new Job("http://10.1.1.8:8000/Screenshots/", "Screenshots")
        // add more here
    );

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        Semaphore sem = new Semaphore(MAX_IN_FLIGHT);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Job job : jobs) {
            List<String> files = new ArrayList<>();
            crawl(job.baseUrl, files);

            System.out.println(job.baseUrl + " -> " + files.size() + " files");

            for (String url : files) {
                sem.acquire();
                futures.add(
                    download(url, job).whenComplete((r, e) -> sem.release())
                );
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();
        System.out.println("ALL DONE");
    }

    static void crawl(String url, List<String> files) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        String body = client.send(req, HttpResponse.BodyHandlers.ofString()).body();

        Matcher m = linkPattern.matcher(body);
        while (m.find()) {
            String href = m.group(1);
            if (href.equals("../")) continue;

            String full = url + href;
            if (href.endsWith("/")) {
                crawl(full, files);
            } else {
                files.add(full);
            }
        }
    }

    static CompletableFuture<Void> download(String url, Job job) {
        try {
            String rel = url.substring(job.baseUrl.length());
            Path out = job.dest.resolve(rel);

            Files.createDirectories(out.getParent());

            // Skip existing files (resume-safe)
            if (Files.exists(out))
                return CompletableFuture.completedFuture(null);

            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();

            return client.sendAsync(req, HttpResponse.BodyHandlers.ofFile(out))
                    .thenAccept(r -> {});

        } catch (Exception e) {
            System.err.println("FAIL: " + url + " -> " + e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
