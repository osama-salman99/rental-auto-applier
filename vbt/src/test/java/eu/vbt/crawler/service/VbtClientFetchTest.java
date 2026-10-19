package eu.vbt.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link VbtClient#fetch} against a fake "curl" script so the retry policy is pinned:
 * a transient timeout (exit 28) is retried and can succeed, but a hard error is not retried.
 * The regression this guards is the 160-timeouts/12h burst where a single slow vb&t response
 * threw away the whole crawl cycle.
 */
class VbtClientFetchTest {

    private static final String UA = "test-agent";

    /** Writes an executable fake-curl script that runs {@code body} (a /bin/sh snippet). */
    private static Path fakeCurl(Path dir, String name, String body) throws IOException {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }

    @Test
    void retriesTransientTimeoutThenSucceeds(@TempDir Path dir) throws IOException {
        Path counter = dir.resolve("count");
        // First invocation exits 28 (curl's "operation timed out"); the second prints the body.
        Path curl = fakeCurl(dir, "curl-retry",
                "c=\"" + counter + "\"\n"
                        + "n=$(cat \"$c\" 2>/dev/null || echo 0); n=$((n+1)); echo \"$n\" > \"$c\"\n"
                        + "if [ \"$n\" -le 1 ]; then exit 28; fi\n"
                        + "printf 'OK-BODY'\n");

        VbtClient client = new VbtClient(curl.toString(), UA);
        String html = client.fetch("https://vbtverhuurmakelaars.nl/woningen");

        assertThat(html).isEqualTo("OK-BODY");
        assertThat(Files.readAllLines(counter)).containsExactly("2"); // invoked twice = retried once
    }

    @Test
    void doesNotRetryHardError(@TempDir Path dir) throws IOException {
        Path counter = dir.resolve("count");
        // Exit 7 = "failed to connect": not transient, so it must be attempted exactly once.
        Path curl = fakeCurl(dir, "curl-hard",
                "c=\"" + counter + "\"\n"
                        + "n=$(cat \"$c\" 2>/dev/null || echo 0); n=$((n+1)); echo \"$n\" > \"$c\"\n"
                        + "exit 7\n");

        VbtClient client = new VbtClient(curl.toString(), UA);
        String html = client.fetch("https://vbtverhuurmakelaars.nl/woningen");

        assertThat(html).isNull();
        assertThat(Files.readAllLines(counter)).containsExactly("1"); // no retry on a hard error
    }

    @Test
    void blankBodyIsTreatedAsNoResult(@TempDir Path dir) throws IOException {
        Path curl = fakeCurl(dir, "curl-blank", "exit 0"); // exits 0 with no output
        VbtClient client = new VbtClient(curl.toString(), UA);
        assertThat(client.fetch("https://vbtverhuurmakelaars.nl/woningen")).isNull();
    }
}
