package eu.nederwoon.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link NederwoonClient#fetch} against a fake "curl" script so the retry policy is
 * pinned: a transient timeout (exit 28) is retried and can succeed, a hard error is not. Mirrors
 * the sibling vb&t crawler's VbtClientFetchTest. fetch() never touches the config repository, so
 * a null repo is fine here.
 */
class NederwoonClientFetchTest {

    private static final String UA = "test-agent";

    private static Path fakeCurl(Path dir, String name, String body) throws IOException {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }

    @Test
    void retriesTransientTimeoutThenSucceeds(@TempDir Path dir) throws IOException {
        Path counter = dir.resolve("count");
        Path curl = fakeCurl(dir, "curl-retry",
                "c=\"" + counter + "\"\n"
                        + "n=$(cat \"$c\" 2>/dev/null || echo 0); n=$((n+1)); echo \"$n\" > \"$c\"\n"
                        + "if [ \"$n\" -le 1 ]; then exit 28; fi\n"
                        + "printf 'OK-BODY'\n");

        NederwoonClient client = new NederwoonClient(null, curl.toString(), UA);
        String html = client.fetch("https://nederwoon.nl/search?city=x&page=1");

        assertThat(html).isEqualTo("OK-BODY");
        assertThat(Files.readAllLines(counter)).containsExactly("2"); // retried once
    }

    @Test
    void doesNotRetryHardError(@TempDir Path dir) throws IOException {
        Path counter = dir.resolve("count");
        Path curl = fakeCurl(dir, "curl-hard",
                "c=\"" + counter + "\"\n"
                        + "n=$(cat \"$c\" 2>/dev/null || echo 0); n=$((n+1)); echo \"$n\" > \"$c\"\n"
                        + "exit 7\n");

        NederwoonClient client = new NederwoonClient(null, curl.toString(), UA);
        assertThat(client.fetch("https://nederwoon.nl/search?city=x&page=1")).isNull();
        assertThat(Files.readAllLines(counter)).containsExactly("1"); // no retry on a hard error
    }

    @Test
    void blankBodyIsTreatedAsNoResult(@TempDir Path dir) throws IOException {
        Path curl = fakeCurl(dir, "curl-blank", "exit 0");
        NederwoonClient client = new NederwoonClient(null, curl.toString(), UA);
        assertThat(client.fetch("https://nederwoon.nl/search?city=x&page=1")).isNull();
    }
}
