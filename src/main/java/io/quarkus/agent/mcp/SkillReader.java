package io.quarkus.agent.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads extension skill files (SKILL.md) from the aggregated
 * {@code quarkus-extension-skills} JAR. Skills are static documentation
 * that don't require a running application.
 */
public final class SkillReader {

    private static final Logger LOG = Logger.getLogger(SkillReader.class);

    private static final String SKILLS_PATH_PREFIX = "META-INF/skills/";
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String SKILLS_ARTIFACT_ID = "quarkus-extension-skills";
    private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2";
    private static final Pattern FRONTMATTER_NAME = Pattern.compile("^name:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONTMATTER_DESC = Pattern.compile("^description:\\s*\"(.+)\"$", Pattern.MULTILINE);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record SkillInfo(String name, String description, String content) {
    }

    private SkillReader() {
    }

    /**
     * Reads all available skills for a project by looking up the aggregated
     * {@code quarkus-extension-skills} JAR in the local Maven repository.
     * If the JAR is not found locally, it is downloaded from Maven Central.
     *
     * @param projectDir the absolute path to the Quarkus project
     * @return list of available skills, never null
     */
    public static List<SkillInfo> readSkills(String projectDir) {
        String version = QuarkusVersionDetector.detect(projectDir);
        if (version == null) {
            LOG.debugf("Could not detect Quarkus version for %s", projectDir);
            return List.of();
        }

        Path m2Repo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Path jarPath = resolveSkillsJarPath(version, m2Repo);

        if (!Files.isRegularFile(jarPath)) {
            LOG.infof("Skills JAR not found locally, downloading for version %s", version);
            jarPath = downloadFromMavenRepo(version, jarPath, projectDir);
            if (jarPath == null) {
                LOG.debugf("Could not download skills JAR for version %s", version);
                return List.of();
            }
        }

        try {
            List<SkillInfo> skills = readSkillsFromJar(jarPath);
            LOG.infof("Found %d skills for project %s (version %s)", skills.size(), projectDir, version);
            return skills;
        } catch (IOException e) {
            LOG.debugf("Failed to read skills from %s: %s", jarPath, e.getMessage());
            return List.of();
        }
    }

    /**
     * Parses the YAML frontmatter from a SKILL.md file content.
     */
    static SkillInfo parseFrontmatter(String fullContent) {
        String name = "unknown";
        String description = null;
        String body = fullContent;

        if (fullContent.startsWith("---")) {
            int endIdx = fullContent.indexOf("---", 3);
            if (endIdx > 0) {
                String frontmatter = fullContent.substring(3, endIdx);
                body = fullContent.substring(endIdx + 3).trim();

                Matcher nameMatcher = FRONTMATTER_NAME.matcher(frontmatter);
                if (nameMatcher.find()) {
                    name = nameMatcher.group(1).trim();
                }

                Matcher descMatcher = FRONTMATTER_DESC.matcher(frontmatter);
                if (descMatcher.find()) {
                    description = descMatcher.group(1).trim();
                }
            }
        }

        return new SkillInfo(name, description, body);
    }

    /**
     * Reads all SKILL.md files from a single JAR.
     */
    static List<SkillInfo> readSkillsFromJar(Path jarPath) throws IOException {
        List<SkillInfo> skills = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.startsWith(SKILLS_PATH_PREFIX)
                        && entryName.endsWith(SKILL_FILE_NAME)
                        && !entry.isDirectory()) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        skills.add(parseFrontmatter(content));
                    }
                }
            }
        }
        return skills;
    }

    /**
     * Constructs the path to the aggregated skills JAR in the local Maven repository.
     */
    static Path resolveSkillsJarPath(String version, Path m2Repo) {
        return m2Repo.resolve("io/quarkus")
                .resolve(SKILLS_ARTIFACT_ID)
                .resolve(version)
                .resolve(SKILLS_ARTIFACT_ID + "-" + version + ".jar");
    }

    /**
     * Downloads the skills JAR from a Maven repository and saves it to the local
     * Maven repository path. Resolves the repository URL by checking (in order):
     * project {@code .mvn/maven.config} for a custom settings file,
     * user {@code ~/.m2/settings.xml}, and global {@code ${MAVEN_HOME}/conf/settings.xml}
     * for mirrors. Falls back to Maven Central if no mirror is configured.
     * Returns the path on success, or null on failure.
     */
    static Path downloadFromMavenRepo(String version, Path targetPath, String projectDir) {
        // Don't attempt download for SNAPSHOT versions — they won't be on release repos
        if (version.endsWith("-SNAPSHOT")) {
            LOG.debugf("Skipping remote download for SNAPSHOT version %s", version);
            return null;
        }

        String baseUrl = resolveMavenRepoBaseUrl(projectDir);
        String artifactPath = "/io/quarkus/" + SKILLS_ARTIFACT_ID
                + "/" + version
                + "/" + SKILLS_ARTIFACT_ID + "-" + version + ".jar";
        String url = baseUrl + artifactPath;

        LOG.debugf("Downloading skills JAR from %s", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                Files.createDirectories(targetPath.getParent());
                try (InputStream body = response.body()) {
                    Files.copy(body, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                LOG.infof("Downloaded skills JAR to %s", targetPath);
                return targetPath;
            } else {
                LOG.debugf("Maven repo returned HTTP %d for %s", response.statusCode(), url);
                return null;
            }
        } catch (IOException | InterruptedException e) {
            LOG.debugf("Failed to download skills JAR from %s: %s", url, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Resolves the base URL of the Maven repository to use for downloads.
     * Checks the following locations in order for a mirror that applies to
     * Maven Central, returning the first match:
     * <ol>
     *   <li>{@code <projectDir>/.mvn/maven.config} — if it contains {@code -s <path>},
     *       that settings file is checked for mirrors</li>
     *   <li>{@code ~/.m2/settings.xml} — user-level settings</li>
     *   <li>{@code ${MAVEN_HOME}/conf/settings.xml} — global Maven settings</li>
     * </ol>
     * Falls back to Maven Central if no mirror is found in any location.
     *
     * @param projectDir the project directory (may be null)
     */
    static String resolveMavenRepoBaseUrl(String projectDir) {
        // 1. Check .mvn/maven.config for a custom settings file
        if (projectDir != null) {
            Path customSettings = parseSettingsFromMvnConfig(Path.of(projectDir));
            if (customSettings != null && Files.isRegularFile(customSettings)) {
                String mirrorUrl = parseMirrorUrl(customSettings);
                if (mirrorUrl != null) {
                    LOG.debugf("Using mirror from .mvn/maven.config settings: %s", mirrorUrl);
                    return mirrorUrl;
                }
            }
        }

        // 2. Check user-level settings.xml
        Path userSettings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
        if (Files.isRegularFile(userSettings)) {
            String mirrorUrl = parseMirrorUrl(userSettings);
            if (mirrorUrl != null) {
                LOG.debugf("Using mirror from user settings.xml: %s", mirrorUrl);
                return mirrorUrl;
            }
        }

        // 3. Check global Maven settings.xml
        Path globalSettings = resolveGlobalSettingsPath();
        if (globalSettings != null && Files.isRegularFile(globalSettings)) {
            String mirrorUrl = parseMirrorUrl(globalSettings);
            if (mirrorUrl != null) {
                LOG.debugf("Using mirror from global settings.xml: %s", mirrorUrl);
                return mirrorUrl;
            }
        }

        return MAVEN_CENTRAL_BASE;
    }

    /**
     * Parses {@code .mvn/maven.config} in the project directory for a {@code -s}
     * or {@code --settings} flag pointing to a custom settings file.
     * Returns the resolved path, or null if not found.
     */
    static Path parseSettingsFromMvnConfig(Path projectDir) {
        Path configFile = projectDir.resolve(".mvn/maven.config");
        if (!Files.isRegularFile(configFile)) {
            return null;
        }
        try {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            String[] tokens = content.trim().split("\\s+");
            for (int i = 0; i < tokens.length - 1; i++) {
                if (tokens[i].equals("-s") || tokens[i].equals("--settings")) {
                    Path settingsPath = Path.of(tokens[i + 1]);
                    // Resolve relative paths against the project directory
                    if (!settingsPath.isAbsolute()) {
                        settingsPath = projectDir.resolve(settingsPath);
                    }
                    return settingsPath.normalize();
                }
            }
        } catch (IOException e) {
            LOG.debugf("Failed to read .mvn/maven.config: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Resolves the path to the global Maven {@code settings.xml}.
     * Checks {@code MAVEN_HOME} and {@code M2_HOME} environment variables,
     * then falls back to the {@code maven.home} system property.
     */
    static Path resolveGlobalSettingsPath() {
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null) {
            mavenHome = System.getenv("M2_HOME");
        }
        if (mavenHome == null) {
            mavenHome = System.getProperty("maven.home");
        }
        if (mavenHome != null) {
            return Path.of(mavenHome, "conf", "settings.xml");
        }
        return null;
    }

    /**
     * Parses {@code settings.xml} for a mirror that applies to Maven Central.
     * Returns the mirror URL (without trailing slash), or null if none found.
     */
    static String parseMirrorUrl(Path settingsFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable external entities for security
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(settingsFile.toFile());

            NodeList mirrors = doc.getElementsByTagName("mirror");
            for (int i = 0; i < mirrors.getLength(); i++) {
                Element mirror = (Element) mirrors.item(i);
                String mirrorOf = getChildText(mirror, "mirrorOf");
                String url = getChildText(mirror, "url");

                if (mirrorOf != null && url != null && mirrorOfMatchesCentral(mirrorOf)) {
                    // Strip trailing slash for consistent path joining
                    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to parse settings.xml at %s: %s", settingsFile, e.getMessage());
        }
        return null;
    }

    /**
     * Checks whether a {@code mirrorOf} value applies to Maven Central.
     * Handles common patterns: {@code central}, {@code *}, {@code external:*},
     * and comma-separated lists containing these values (excluding negations).
     */
    static boolean mirrorOfMatchesCentral(String mirrorOf) {
        String trimmed = mirrorOf.trim();

        // Exact matches
        if (trimmed.equals("*") || trimmed.equals("central") || trimmed.equals("external:*")) {
            return true;
        }

        // Comma-separated: check for central or wildcard, but respect negations like !central
        if (trimmed.contains(",")) {
            String[] parts = trimmed.split(",");
            boolean matched = false;
            for (String part : parts) {
                String p = part.trim();
                if (p.equals("!central")) {
                    return false;
                }
                if (p.equals("*") || p.equals("central") || p.equals("external:*")) {
                    matched = true;
                }
            }
            return matched;
        }

        return false;
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() > 0) {
            String text = children.item(0).getTextContent();
            return text != null ? text.trim() : null;
        }
        return null;
    }
}
