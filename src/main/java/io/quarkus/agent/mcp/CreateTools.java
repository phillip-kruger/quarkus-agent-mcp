package io.quarkus.agent.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tool for creating new Quarkus applications.
 * Uses the first available tool in this order:
 * 1. Quarkus CLI (quarkus create app)
 * 2. Maven (mvn io.quarkus.platform:quarkus-maven-plugin:create)
 * 3. JBang (jbang quarkus@quarkusio create app)
 */
public class CreateTools {

    private static final Logger LOG = Logger.getLogger(CreateTools.class);

    @Inject
    QuarkusProcessManager processManager;

    // Maven coordinate segments: letters, digits, dots, hyphens, underscores
    private static final Pattern VALID_MAVEN_ID = Pattern.compile("^[a-zA-Z0-9._-]+$");

    // Extensions: comma-separated list of extension short names
    private static final Pattern VALID_EXTENSIONS = Pattern.compile("^[a-zA-Z0-9._,:-]+$");

    // Cache which command is available — doesn't change during the lifetime of the server
    private volatile String cachedCreateCommand;

    @Tool(name = "quarkus/create", description = "Create a new Quarkus application and auto-start it in dev mode. "
            + "RULES: 1) NEVER implement features manually when a Quarkus extension exists — "
            + "always search for and add the right extension first (e.g. use quarkus-qute for templating, "
            + "quarkus-smallrye-openapi for API docs, quarkus-smallrye-health for health checks). "
            + "Use quarkus/searchDocs or quarkus/searchTools query='extension' to find extensions. "
            + "2) Use quarkus/skills and quarkus/searchDocs BEFORE writing any code. "
            + "3) ALWAYS write tests for every feature — no exceptions. "
            + "4) Keep README.md updated with app description, features, endpoints, and Quarkus guide links after every change.")
    ToolResponse create(
            @ToolArg(description = "Absolute path to the directory where the project will be created. "
                    + "A subdirectory named after the artifactId will be created inside this directory.") String outputDir,
            @ToolArg(description = "The Maven groupId for the project (e.g. 'com.example')", required = false) String groupId,
            @ToolArg(description = "The Maven artifactId for the project (e.g. 'my-app').", required = false) String artifactId,
            @ToolArg(description = "Comma-separated list of Quarkus extensions to include "
                    + "(e.g. 'rest-jackson,hibernate-orm-panache,jdbc-postgresql')", required = false) String extensions,
            @ToolArg(description = "Build tool to use: 'maven' or 'gradle' (default: maven)", required = false) String buildTool,
            @ToolArg(description = "Quarkus platform version to use (e.g. '3.21.2', '999-SNAPSHOT'). "
                    + "If omitted, uses the latest release.", required = false) String quarkusVersion) {
        try {
            String resolvedGroupId = (groupId != null && !groupId.isBlank()) ? groupId : "org.acme";
            String resolvedArtifactId = (artifactId != null && !artifactId.isBlank()) ? artifactId : "quarkus-app";

            if (!VALID_MAVEN_ID.matcher(resolvedGroupId).matches()) {
                return ToolResponse.error("Invalid groupId: must contain only letters, digits, dots, hyphens, underscores.");
            }
            if (!VALID_MAVEN_ID.matcher(resolvedArtifactId).matches()) {
                return ToolResponse.error("Invalid artifactId: must contain only letters, digits, dots, hyphens, underscores.");
            }
            if (extensions != null && !extensions.isBlank() && !VALID_EXTENSIONS.matcher(extensions).matches()) {
                return ToolResponse.error("Invalid extensions: must contain only letters, digits, dots, hyphens, commas, colons.");
            }
            if (quarkusVersion != null && !quarkusVersion.isBlank() && !VALID_MAVEN_ID.matcher(quarkusVersion).matches()) {
                return ToolResponse.error("Invalid quarkusVersion: must contain only letters, digits, dots, hyphens, underscores.");
            }

            File outDir = new File(outputDir);
            if (!outDir.isDirectory()) {
                return ToolResponse.error("Output directory does not exist: " + outputDir);
            }

            List<String> command = buildCommand(outDir, resolvedGroupId, resolvedArtifactId, extensions, buildTool,
                    quarkusVersion);
            LOG.infof("Creating Quarkus app: %s", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(outDir)
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output;
            int exitCode;
            try {
                output = captureOutput(process);
                exitCode = process.waitFor();
            } finally {
                process.destroyForcibly();
            }

            if (exitCode != 0) {
                return ToolResponse.error("Project creation failed (exit " + exitCode + "):\n" + output);
            }

            String projectDir = new File(outDir, resolvedArtifactId).getAbsolutePath();

            // Generate CLAUDE.md with Quarkus-specific instructions
            generateClaudeMd(projectDir, extensions);

            // Auto-start the app in dev mode
            try {
                processManager.start(projectDir, buildTool);
                LOG.infof("Auto-started Quarkus app at: %s", projectDir);
                return ToolResponse.success("Quarkus project created and starting in dev mode at: " + projectDir
                        + "\n\nNEXT STEPS:"
                        + "\n1. Before implementing ANY feature, search for a Quarkus extension that provides it. "
                        + "Use quarkus/searchDocs to find extensions. NEVER roll your own solution when an extension exists "
                        + "(e.g. use quarkus-qute for templates, quarkus-smallrye-health for health checks, "
                        + "quarkus-smallrye-openapi for API docs, quarkus-mailer for email). "
                        + "Add extensions via quarkus/searchTools query='extension' → quarkus/callTool."
                        + "\n2. Use quarkus/skills to learn the correct patterns, testing approaches, and configuration for each extension."
                        + "\n3. Use quarkus/searchDocs to look up additional Quarkus APIs and best practices."
                        + "\n4. Write your code AND tests. Always include tests for every feature."
                        + "\n5. Run tests with quarkus/callTool: use 'devui-testing_runTests' to run all tests, "
                        + "'devui-testing_runAffectedTests' to run only tests affected by your changes, "
                        + "or 'devui-testing_runTest' with arguments {\"className\":\"com.example.MyTest\"} for a specific test."
                        + "\n6. Hot reload is triggered when tests run — do NOT restart the app."
                        + "\n7. Update README.md with: app description, features, endpoints, how to run, and links to Quarkus guides."
                        + "\n8. After core features work, suggest to the user: security, observability, health checks, OpenAPI.");
            } catch (Exception startError) {
                LOG.warnf("Project created but failed to auto-start: %s", startError.getMessage());
                return ToolResponse.success("Quarkus project created at: " + projectDir
                        + "\nAuto-start failed: " + startError.getMessage()
                        + "\nUse quarkus/start with projectDir='" + projectDir + "' to start it manually.");
            }
        } catch (Exception e) {
            LOG.error("Failed to create Quarkus project", e);
            return ToolResponse.error("Failed to create project: " + e.getMessage());
        }
    }

    private List<String> buildCommand(File outputDir, String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion) {
        String cmd = resolveCreateCommand();
        return switch (cmd) {
            case "quarkus" -> buildQuarkusCliCommand("quarkus", groupId, artifactId, extensions, buildTool,
                    quarkusVersion);
            case "mvn" -> buildMavenCommand(groupId, artifactId, extensions, buildTool, quarkusVersion);
            case "jbang" -> buildJBangCommand(groupId, artifactId, extensions, buildTool, quarkusVersion);
            default -> throw new IllegalStateException("Unexpected command: " + cmd);
        };
    }

    private String resolveCreateCommand() {
        if (cachedCreateCommand != null) {
            return cachedCreateCommand;
        }
        if (isCommandAvailable("quarkus")) {
            LOG.info("Using Quarkus CLI to create projects");
            cachedCreateCommand = "quarkus";
        } else if (isCommandAvailable("mvn")) {
            LOG.info("Quarkus CLI not found, using Maven plugin");
            cachedCreateCommand = "mvn";
        } else if (isCommandAvailable("jbang")) {
            LOG.info("Neither Quarkus CLI nor Maven found, using JBang");
            cachedCreateCommand = "jbang";
        } else {
            throw new IllegalStateException(
                    "No tool found to create Quarkus projects. Install one of: "
                            + "Quarkus CLI (https://quarkus.io/guides/cli-tooling), "
                            + "Maven (https://maven.apache.org), or "
                            + "JBang (https://jbang.dev).");
        }
        return cachedCreateCommand;
    }

    private List<String> buildQuarkusCliCommand(String quarkusCmd, String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion) {
        List<String> cmd = new ArrayList<>();
        cmd.add(quarkusCmd);
        cmd.add("create");
        cmd.add("app");
        cmd.add(groupId + ":" + artifactId);
        cmd.add("--no-code");
        cmd.add("--batch-mode");

        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            cmd.add("--platform-bom=io.quarkus:quarkus-bom:" + quarkusVersion);
        }
        if (extensions != null && !extensions.isBlank()) {
            cmd.add("--extension=" + extensions);
        }
        if ("gradle".equalsIgnoreCase(buildTool)) {
            cmd.add("--gradle");
        }

        return cmd;
    }

    private List<String> buildJBangCommand(String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion) {
        List<String> cmd = new ArrayList<>();
        cmd.add("jbang");
        cmd.add("quarkus@quarkusio");
        cmd.add("create");
        cmd.add("app");
        cmd.add(groupId + ":" + artifactId);
        cmd.add("--no-code");
        cmd.add("--batch-mode");

        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            cmd.add("--platform-bom=io.quarkus:quarkus-bom:" + quarkusVersion);
        }
        if (extensions != null && !extensions.isBlank()) {
            cmd.add("--extension=" + extensions);
        }
        if ("gradle".equalsIgnoreCase(buildTool)) {
            cmd.add("--gradle");
        }

        return cmd;
    }

    private List<String> buildMavenCommand(String groupId, String artifactId,
            String extensions, String buildTool, String quarkusVersion) {
        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
        String pluginGroupId = "io.quarkus.platform";
        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            pluginGroupId = "io.quarkus";
        }
        cmd.add(pluginGroupId + ":quarkus-maven-plugin:"
                + (quarkusVersion != null && !quarkusVersion.isBlank() ? quarkusVersion + ":" : "")
                + "create");
        cmd.add("-DprojectGroupId=" + groupId);
        cmd.add("-DprojectArtifactId=" + artifactId);
        cmd.add("-DnoCode=true");
        cmd.add("-B");

        if (quarkusVersion != null && !quarkusVersion.isBlank()) {
            cmd.add("-DplatformGroupId=io.quarkus");
            cmd.add("-DplatformVersion=" + quarkusVersion);
        }
        if (extensions != null && !extensions.isBlank()) {
            cmd.add("-Dextensions=" + extensions);
        }
        if ("gradle".equalsIgnoreCase(buildTool)) {
            cmd.add("-DbuildTool=gradle");
        }

        return cmd;
    }

    private boolean isCommandAvailable(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    private String captureOutput(Process process) throws IOException {
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

    private void generateClaudeMd(String projectDir, String extensions) {
        try {
            String content = """
                    # CLAUDE.md — Quarkus Project Instructions

                    This is a Quarkus application. Follow these rules when working on this project.

                    ## Required Workflow

                    1. **Use quarkus/update (via subagent) when returning to this project** — checks if the Quarkus version is up-to-date and suggests upgrades.
                    2. **Use quarkus/skills BEFORE writing any code or tests** — it contains extension-specific patterns, testing approaches, and common pitfalls that prevent mistakes.
                    3. **Use quarkus/searchDocs for Quarkus documentation** — do NOT use generic documentation tools (Context7, web search). The Quarkus doc search is version-aware and more accurate.
                    4. **Use quarkus/searchTools to discover Dev MCP tools** on the running app for testing, config changes, and extension management.
                    5. **Use quarkus/callTool to invoke Dev MCP tools** — run tests, add extensions, update configuration. Do NOT run Maven/Gradle commands manually.
                    6. **Hot reload is automatic** — the app recompiles when accessed. Do NOT restart the app after code changes.

                    ## Rules

                    - NEVER implement features manually when a Quarkus extension exists — search for and add the right extension first.
                    - ALWAYS write tests for every feature — no exceptions.
                    - ALWAYS keep README.md updated with app description, features, endpoints, and Quarkus guide links.
                    - Use `@QuarkusTest` for integration tests — Dev Services auto-starts backing services (databases, messaging, etc.).
                    - Use `%dev.` and `%test.` profile prefixes for dev/test configuration — never hardcode connection URLs without a profile prefix.

                    ## Testing

                    ALWAYS run tests using a **subagent** so the main conversation stays responsive:

                    ```
                    Use the Agent tool to launch a subagent with this prompt:
                      "Run the Quarkus tests for project <projectDir> using quarkus/callTool
                       with toolName 'devui-testing_runTests'. Analyze the results and report
                       which tests passed, failed, or errored. If tests fail, include the
                       failure messages and suggest fixes."
                    ```

                    - Use `devui-testing_runTests` to run all tests.
                    - Use `devui-testing_runTest` with arguments `{"className":"com.example.MyTest"}` to run a specific test class.
                    - Do NOT run Maven/Gradle test commands manually — the Dev MCP test tools handle compilation, hot reload, and result reporting.
                    - After fixing test failures, re-run tests with a subagent to verify the fix.
                    """;
            Files.writeString(Path.of(projectDir, "CLAUDE.md"), content, StandardCharsets.UTF_8);
            LOG.debugf("Generated CLAUDE.md in %s", projectDir);
        } catch (IOException e) {
            LOG.debugf("Failed to generate CLAUDE.md: %s", e.getMessage());
        }
    }
}
