package eu.rebo.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ReboFormClientTest {

    @Test
    void parsesUrlEncodedXsrfTokenFromNetscapeJar(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("jar.txt");
        Files.writeString(jar, String.join("\n",
                "# Netscape HTTP Cookie File",
                "#HttpOnly_cms.rebogroep.nl\tFALSE\t/\tTRUE\t0\trebo_groep_session\tsess%3Dvalue",
                "cms.rebogroep.nl\tFALSE\t/\tTRUE\t0\tXSRF-TOKEN\ttok%3D%3Dend") + "\n");

        // %3D decodes to '=' — Laravel needs the decoded token in the X-XSRF-TOKEN header.
        assertThat(ReboFormClient.readXsrfToken(jar)).isEqualTo("tok==end");
    }

    @Test
    void parsesTrailingHttpStatusFromCurlOutput() {
        String out = "{\"message\":\"ok\"}\n__HTTP__200";
        assertThat(ReboFormClient.parseTrailingStatus(out)).isEqualTo(200);
        assertThat(ReboFormClient.stripTrailingStatus(out)).isEqualTo("{\"message\":\"ok\"}");
    }

    @Test
    void returnsZeroStatusWhenMarkerMissing() {
        assertThat(ReboFormClient.parseTrailingStatus("garbage")).isZero();
    }

    /**
     * Drives the full two-call flow against a fake curl that writes a jar on the handshake and echoes
     * a 200 on the submit — exercising arg construction, the CSRF handshake and result parsing without
     * touching the network.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void submitsThroughCurlAndReportsAccepted(@TempDir Path dir) throws IOException {
        // Prepare the cookie jar in Java (real tabs, URL-encoded token) so the fake curl need only
        // copy it — avoids embedding tab/percent escapes in a shell heredoc.
        Path jarSrc = dir.resolve("jar-src.txt");
        Files.writeString(jarSrc,
                "# Netscape HTTP Cookie File\n"
                + "cms.rebogroep.nl\tFALSE\t/\tTRUE\t0\tXSRF-TOKEN\ttok%3D\n");

        Path fakeCurl = dir.resolve("fake-curl.sh");
        Files.writeString(fakeCurl, """
                #!/usr/bin/env bash
                jar=""
                url=""
                prev=""
                for a in "$@"; do
                  [ "$prev" = "-c" ] && jar="$a"
                  case "$a" in https://*) url="$a";; esac
                  prev="$a"
                done
                if [[ "$url" == *"/sanctum/csrf-cookie" ]]; then
                  cp "%s" "$jar"
                  printf '204'
                  exit 0
                fi
                printf '{"data":"ok"}\\n__HTTP__200'
                exit 0
                """.formatted(jarSrc.toString()));
        Files.setPosixFilePermissions(fakeCurl, PosixFilePermissions.fromString("rwxr-xr-x"));

        ReboFormClient client = new ReboFormClient(fakeCurl.toString(), "test-agent");
        ApplyPayload payload = new ApplyPayload("Jan", "Jansen", "a@b.nl", "+31600000000",
                "Employer", null, "obj-1", "Some Street 1", "1000 AA");

        ReboFormClient.SubmitResult result = client.submit(payload);

        assertThat(result.accepted()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
    }
}
