import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

class ManifestBuilder {

    static void build(Path root, Path outFile, String name)
            throws Exception {

        Files.createDirectories(outFile.getParent());

        List<Path> files = new ArrayList<>();

        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .forEach(files::add);
        }

       
        files.sort(Comparator.comparing(
                p -> root.relativize(p).toString()));

        try (BufferedWriter w = Files.newBufferedWriter(
                outFile, StandardCharsets.UTF_8)) {

            w.write("{\n");
            w.write("  \"version\": 1,\n");
            w.write("  \"algorithm\": \"SHA-256\",\n");
            w.write("  \"generated_at\": \"" + Instant.now() + "\",\n");
            w.write("  \"root\": \"" + escape(name) + "\",\n");
            w.write("  \"files\": {\n");

            for (int i = 0; i < files.size(); i++) {
                Path p = files.get(i);

                String rel = root.relativize(p)
                        .toString()
                        .replace("\\", "/");

                long size = Files.size(p);
                String hash = sha256(p);

                w.write("    \"" + escape(rel) + "\": {\n");
                w.write("      \"size\": " + size + ",\n");
                w.write("      \"hash\": \"" + hash + "\"\n");
                w.write("    }");

                if (i < files.size() - 1)
                    w.write(",");

                w.write("\n");
            }

            w.write("  }\n");
            w.write("}\n");
        }
    }

    // I am extremely tired. Help me.

    static void buildAtomic(Path root, Path outFile, String name)
            throws Exception {

        Path tmp = outFile.resolveSibling(
                outFile.getFileName().toString() + ".json.tmp");

        build(root, tmp, name);

        Files.move(tmp, outFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    // Pls

    static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
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

    static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
