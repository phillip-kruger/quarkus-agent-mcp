package io.quarkus.agent.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;

final class ProcessUtils {

    private static final Logger LOG = Logger.getLogger(ProcessUtils.class);

    private ProcessUtils() {
    }

    static boolean isCommandAvailable(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    static String captureOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                LOG.debug(line);
            }
            return sb.toString().trim();
        }
    }
}
