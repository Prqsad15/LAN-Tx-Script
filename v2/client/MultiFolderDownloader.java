import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MultiFolderDownloader {

    // ================= CONFIG =================
    // Change all of this according to your needs
    static final int THREADS = 32;
    static final int MAX_IN_FLIGHT = 128;
    static final String MANIFEST = "manifest.json";

    static boolean VERIFY = false;

    static HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static Pattern linkPattern =
            Pattern.compile("href=\"([^\"]+)\"");

    static StateManager state;

    // ================= JOB =================
    // OMFG I USED THE J SLUR NOOOOOO EVERYONE READING THIS WILL GET JUMPSCARED NOOOOOOOOOOO
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
    );

    // ================= MAIN =================

    public static void main(String[] args) throws Exception {

        state = new StateManager(Paths.get("download.state"));
        VERIFY = Arrays.asList(args).contains("--verify");

        ExecutorService pool =
                Executors.newFixedThreadPool(THREADS);
        Semaphore sem = new Semaphore(MAX_IN_FLIGHT);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Job job : jobs) {
            List<String> files = new ArrayList<>();
            crawl(job.baseUrl, files);
            System.out.println(job.baseUrl + " -> " + files.size() + " files");

            for (String url : files) {
                sem.acquire();
                futures.add(
                        downloadIfNeeded(url, job)
                                .whenComplete((r, e) -> sem.release())
                );
            }
        }

        CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        ).join();

        pool.shutdown();

        if (VERIFY) {
            System.out.println("Starting verification...");
            verifyAll();
        }

        System.out.println("ALL DONE");
    }

    //This is da spider, since it crawls ahahhaha >:D

    static void crawl(String url, List<String> files) throws Exception {

        HttpRequest req =
                HttpRequest.newBuilder(URI.create(url)).GET().build();

        String body =
                client.send(req, HttpResponse.BodyHandlers.ofString()).body();

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

    // Completable future sounds so cool as a name / word or whatever, it makes you imagine... (IDK how to describe it)

    static CompletableFuture<Void> downloadIfNeeded(
            String url, Job job) {

        try {
            String rel = url.substring(job.baseUrl.length());
            Path out = job.dest.resolve(rel);

            if (state.isVerified(rel)) {
                return CompletableFuture.completedFuture(null);
            }

            Files.createDirectories(out.getParent());

            if (headMatches(url, out)) {
                state.record(rel, Files.size(out), Status.DOWNLOADED);
                return CompletableFuture.completedFuture(null);
            }

            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(url)).GET().build();

            return client.sendAsync(
                    req, HttpResponse.BodyHandlers.ofFile(out))
                    .thenAccept(r -> {
                        try {
                            state.record(
                                    rel, Files.size(out), Status.DOWNLOADED);
                        } catch (Exception ignored) {}
                    });

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static boolean headMatches(String url, Path local)
            throws Exception {

        if (!Files.exists(local)) return false;

        long localSize = Files.size(local);
        long remoteSize = fetchRemoteSize(url);

        return remoteSize > 0 && localSize == remoteSize;
    }

    static long fetchRemoteSize(String url) throws Exception {

        HttpRequest req =
                HttpRequest.newBuilder(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build();

        HttpResponse<Void> res =
                client.send(req, HttpResponse.BodyHandlers.discarding());

        return res.headers()
                .firstValue("Content-Length")
                .map(Long::parseLong)
                .orElse(-1L);
    }

    // This is going shockingly well lol

	static void verifyAll() throws Exception {

		for (Job job : jobs) {

			Path manifestPath =
					job.dest.resolve("manifest.json");

			Manifest manifest =
					ManifestParser.parse(manifestPath);

			for (var e : manifest.files.entrySet()) {

				String relPath = e.getKey();
				Manifest.Entry meta = e.getValue();

				if (state.isVerified(relPath))
					continue;

				boolean ok = Verifier.verifyFile(
						job.dest,
						relPath,
						meta,
						VERIFY
				);

				if (!ok) {
					System.out.println("Redownloading: " + relPath);

					String url = job.baseUrl + relPath;
					Path out = job.dest.resolve(relPath);

					Files.deleteIfExists(out);
					Files.createDirectories(out.getParent());

					HttpRequest req =
							HttpRequest.newBuilder(URI.create(url))
									.GET()
									.build();

					client.send(req,
							HttpResponse.BodyHandlers.ofFile(out));

					Status st = VERIFY
							? Status.VERIFIED
							: Status.DOWNLOADED;

					state.record(relPath, Files.size(out), st);
				}
			}
		}
	}


/* ================= MANIFEST ================= */

class Manifest {

    static class Entry {
        final long size;
        final String hash;

        Entry(long size, String hash) {
            this.size = size;
            this.hash = hash;
        }
    }

    final Map<String, Entry> files = new HashMap<>();
}

/* My excitement is running out lol.*/

class ManifestParser {

    static Manifest parse(Path manifestFile) throws IOException {

        Manifest m = new Manifest();

        try (BufferedReader r =
                     Files.newBufferedReader(
                             manifestFile, StandardCharsets.UTF_8)) {

            String line;
            String currentFile = null;
            Long size = null;
            String hash = null;

            while ((line = r.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("\"") && line.endsWith("\": {")) {
                    currentFile =
                            unquote(line.substring(0, line.indexOf("\":")));
                }

                else if (line.startsWith("\"size\"")) {
                    size = Long.parseLong(
                            line.replaceAll("\\D+", ""));
                }

                else if (line.startsWith("\"hash\"")) {
                    hash = unquote(
                            line.split(":")[1]
                                    .trim()
                                    .replace(",", ""));
                }

                else if (line.equals("}") && currentFile != null) {
                    m.files.put(
                            currentFile,
                            new Manifest.Entry(size, hash));
                    currentFile = null;
                    size = null;
                    hash = null;
                }
            }
        }

        return m;
    }

    private static String unquote(String s) {
        return s.replace("\"", "")
                .replace("\\/", "/")
                .replace("\\\\", "\\");
    }
}

/* ================= VERIFIER ================= */

class Verifier {

    static boolean verifyFile(
            Path root,
            String relPath,
            Manifest.Entry entry,
            boolean deepVerify
    ) throws Exception {

        Path file = root.resolve(relPath);

        if (!Files.exists(file)) return false;
        if (Files.size(file) != entry.size) return false;

        if (!deepVerify) return true;

        String localHash = sha256(file);
        return localHash.equalsIgnoreCase(entry.hash);
    }

    static String sha256(Path file) throws Exception {

        MessageDigest md =
                MessageDigest.getInstance("SHA-256");

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest())
            sb.append(String.format("%02x", b));

        return sb.toString();
    }
}
