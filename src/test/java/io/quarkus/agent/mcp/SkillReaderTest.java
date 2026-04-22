package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void parseFrontmatterExtractsNameAndDescription() {
        String content = """
                ---
                name: quarkus-rest
                description: "A Jakarta REST implementation"
                license: Apache-2.0
                metadata:
                  guide: https://quarkus.io/guides/rest
                ---

                ### REST Endpoints
                Use @Path and @GET for endpoints.
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals("quarkus-rest", info.name());
        assertEquals("A Jakarta REST implementation", info.description());
        assertTrue(info.content().contains("### REST Endpoints"));
        assertFalse(info.content().contains("---"));
        assertEquals(SkillReader.SkillMode.ENHANCE, info.mode());
    }

    @Test
    void parseFrontmatterHandlesMissingDescription() {
        String content = """
                ---
                name: quarkus-arc
                license: Apache-2.0
                ---

                ### CDI
                Use @Inject for DI.
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals("quarkus-arc", info.name());
        assertNull(info.description());
        assertTrue(info.content().contains("### CDI"));
    }

    @Test
    void parseFrontmatterHandlesNoFrontmatter() {
        String content = "### Just Markdown\nNo frontmatter here.";

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals("unknown", info.name());
        assertNull(info.description());
        assertTrue(info.content().contains("### Just Markdown"));
    }

    @Test
    void readSkillsFromJarFindsSkillFiles() throws Exception {
        Path jarPath = tempDir.resolve("quarkus-extension-skills-999-SNAPSHOT.jar");
        String skillMd = """
                ---
                name: quarkus-rest
                description: "REST extension"
                license: Apache-2.0
                ---

                ### REST Endpoints
                Use @Path.
                """;

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("REST extension", skills.get(0).description());
        assertTrue(skills.get(0).content().contains("### REST Endpoints"));
    }

    @Test
    void readSkillsFromJarFindsMultipleSkills() throws Exception {
        Path jarPath = tempDir.resolve("quarkus-extension-skills-999-SNAPSHOT.jar");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    ---

                    ### REST
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-arc/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-arc
                    description: "CDI extension"
                    ---

                    ### CDI
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath);

        assertEquals(2, skills.size());
    }

    @Test
    void readSkillsFromJarReturnsEmptyForNoSkills() throws Exception {
        Path jarPath = tempDir.resolve("some-lib.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/quarkus-extension.yaml"));
            jos.write("name: something".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath);

        assertTrue(skills.isEmpty());
    }

    @Test
    void resolveSkillsJarPathConstructsCorrectPath() {
        Path result = SkillReader.resolveSkillsJarPath(
                "3.21.2",
                Path.of("/home/user/.m2/repository"));

        assertEquals(
                Path.of("/home/user/.m2/repository/io/quarkus/quarkus-extension-skills/3.21.2/quarkus-extension-skills-3.21.2.jar"),
                result);
    }

    @Test
    void downloadSkipsSnapshotVersions() {
        Path targetPath = tempDir.resolve("skills.jar");
        Path result = SkillReader.downloadFromMavenRepo("999-SNAPSHOT", targetPath, tempDir.toString());
        assertNull(result);
    }

    @Test
    void mirrorOfMatchesCentral() {
        assertTrue(SkillReader.mirrorOfMatchesCentral("central"));
        assertTrue(SkillReader.mirrorOfMatchesCentral("*"));
        assertTrue(SkillReader.mirrorOfMatchesCentral("external:*"));
        assertTrue(SkillReader.mirrorOfMatchesCentral("central,jboss"));
        assertTrue(SkillReader.mirrorOfMatchesCentral("*,!jboss"));
    }

    @Test
    void mirrorOfDoesNotMatchWhenCentralExcluded() {
        assertFalse(SkillReader.mirrorOfMatchesCentral("!central"));
        assertFalse(SkillReader.mirrorOfMatchesCentral("*,!central"));
        assertFalse(SkillReader.mirrorOfMatchesCentral("jboss"));
        assertFalse(SkillReader.mirrorOfMatchesCentral("external:http"));
    }

    @Test
    void readLocalSkillsFindsSkillFiles() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path restDir = skillsDir.resolve("quarkus-rest");
        Files.createDirectories(restDir);
        Files.writeString(restDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Local REST skill"
                ---

                ### Local REST
                Local override content.
                """);

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("Local REST skill", skills.get(0).description());
        assertTrue(skills.get(0).content().contains("Local override content."));
    }

    @Test
    void readLocalSkillsFindsMultipleSkills() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path restDir = skillsDir.resolve("quarkus-rest");
        Path arcDir = skillsDir.resolve("quarkus-arc");
        Files.createDirectories(restDir);
        Files.createDirectories(arcDir);
        Files.writeString(restDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                ---

                ### REST
                """);
        Files.writeString(arcDir.resolve("SKILL.md"), """
                ---
                name: quarkus-arc
                ---

                ### CDI
                """);

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertEquals(2, skills.size());
    }

    @Test
    void readLocalSkillsReturnsEmptyWhenDirDoesNotExist() {
        Path nonExistent = tempDir.resolve("no-such-dir");

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(nonExistent);

        assertTrue(skills.isEmpty());
    }

    @Test
    void readLocalSkillsIgnoresNonSkillFiles() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path restDir = skillsDir.resolve("quarkus-rest");
        Files.createDirectories(restDir);
        Files.writeString(restDir.resolve("README.md"), "Not a skill file");

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertTrue(skills.isEmpty());
    }

    @Test
    void readLocalSkillsFromProjectDir() throws Exception {
        // Simulate a project with skills under .quarkus/skills/
        Path projectSkillsDir = tempDir.resolve(".quarkus/skills/quarkus-rest");
        Files.createDirectories(projectSkillsDir);
        Files.writeString(projectSkillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Project-level REST skill"
                ---

                ### Custom REST patterns for this project
                """);

        Path skillsDir = tempDir.resolve(".quarkus/skills");
        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("Project-level REST skill", skills.get(0).description());
        assertTrue(skills.get(0).content().contains("Custom REST patterns"));
    }

    @Test
    void parseMirrorUrlFromSettingsXml() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <mirrors>
                        <mirror>
                            <id>company-mirror</id>
                            <url>https://artifactory.company.com/maven-central/</url>
                            <mirrorOf>*</mirrorOf>
                        </mirror>
                    </mirrors>
                </settings>
                """);

        String url = SkillReader.parseMirrorUrl(settingsFile);

        assertEquals("https://artifactory.company.com/maven-central", url);
    }

    @Test
    void parseMirrorUrlReturnsNullWhenNoMirror() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <profiles>
                        <profile>
                            <id>default</id>
                        </profile>
                    </profiles>
                </settings>
                """);

        String url = SkillReader.parseMirrorUrl(settingsFile);

        assertNull(url);
    }

    @Test
    void parseMirrorUrlIgnoresNonCentralMirrors() throws Exception {
        Path settingsFile = tempDir.resolve("settings.xml");
        Files.writeString(settingsFile, """
                <settings>
                    <mirrors>
                        <mirror>
                            <id>jboss-mirror</id>
                            <url>https://mirror.example.com/jboss/</url>
                            <mirrorOf>jboss-releases</mirrorOf>
                        </mirror>
                    </mirrors>
                </settings>
                """);

        String url = SkillReader.parseMirrorUrl(settingsFile);

        assertNull(url);
    }

    // --- Enhance mode tests ---

    @Test
    void parseFrontmatterDefaultsToEnhanceMode() {
        String content = """
                ---
                name: quarkus-rest
                description: "REST extension"
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(SkillReader.SkillMode.ENHANCE, info.mode());
    }

    @Test
    void parseFrontmatterParsesEnhanceMode() {
        String content = """
                ---
                name: quarkus-rest
                mode: enhance
                ---

                ### Extra REST patterns
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(SkillReader.SkillMode.ENHANCE, info.mode());
    }

    @Test
    void parseFrontmatterParsesOverrideMode() {
        String content = """
                ---
                name: quarkus-rest
                mode: override
                ---

                ### Fully custom REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(SkillReader.SkillMode.OVERRIDE, info.mode());
    }

    @Test
    void enhanceModeAppendsContentToBaseSkill() throws Exception {
        // Create a JAR with a base skill
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    ---

                    ### Base REST patterns
                    Use @Path and @GET.
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        // Create a local enhance skill
        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                mode: enhance
                ---

                ### Project conventions
                Always use configKey with @RegisterRestClient.
                """);

        // Load and overlay
        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        SkillReader.SkillInfo result = skillMap.get("quarkus-rest");
        assertNotNull(result);
        assertTrue(result.content().contains("### Base REST patterns"), "Should contain base content");
        assertTrue(result.content().contains("### Project conventions"), "Should contain enhanced content");
        assertTrue(result.content().contains("Use @Path and @GET."), "Should preserve base details");
        assertTrue(result.content().contains("Always use configKey"), "Should include enhancement");
        assertEquals("REST extension", result.description(), "Should keep base description when enhance has none");
    }

    @Test
    void overrideModeReplacesBaseSkill() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    ---

                    ### Base REST patterns
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Custom REST"
                mode: override
                ---

                ### Fully custom REST
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        SkillReader.SkillInfo result = skillMap.get("quarkus-rest");
        assertNotNull(result);
        assertFalse(result.content().contains("Base REST patterns"), "Should not contain base content");
        assertTrue(result.content().contains("Fully custom REST"), "Should contain override content");
        assertEquals("Custom REST", result.description());
    }

    @Test
    void enhanceModePreservesBaseDescriptionWhenNotOverridden() throws Exception {
        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                mode: enhance
                ---

                ### Extra patterns
                """);

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        assertEquals(1, local.size());
        assertNull(local.get(0).description(), "Enhance skill without description should be null");
    }

    @Test
    void enhanceModeWithDescriptionOverridesBaseDescription() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "Base description"
                    ---

                    ### Base content
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Enhanced description"
                mode: enhance
                ---

                ### Enhanced content
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        assertEquals("Enhanced description", skillMap.get("quarkus-rest").description());
    }

    // --- writeSkill tests ---

    @Test
    void writeSkillCreatesFileWithCorrectFrontmatter() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### My custom patterns\nUse records for DTOs.",
                "Custom REST skill",
                null,
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true);

        assertTrue(Files.exists(written));
        String content = Files.readString(written);
        assertTrue(content.contains("name: quarkus-rest"));
        assertTrue(content.contains("description: \"Custom REST skill\""));
        assertTrue(content.contains("mode: enhance"));
        assertTrue(content.contains("### My custom patterns"));
        assertEquals(projectDir.resolve(".quarkus/skills/quarkus-rest/SKILL.md"), written);
    }

    @Test
    void writeSkillGlobalScopeWritesToUserDir() throws Exception {
        Path globalDir = tempDir.resolve("global-skills");
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Global patterns",
                null,
                null,
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), globalDir, false);

        assertEquals(globalDir.resolve("quarkus-rest/SKILL.md"), written);
        String content = Files.readString(written);
        assertTrue(content.contains("name: quarkus-rest"));
        assertTrue(content.contains("mode: enhance"));
        assertFalse(content.contains("description:"), "Should not include description when null");
    }

    @Test
    void writeSkillWithOverrideMode() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Full replacement",
                "Override skill",
                null,
                SkillReader.SkillMode.OVERRIDE,
                projectDir.toString(), null, true);

        String content = Files.readString(written);
        assertTrue(content.contains("mode: override"));
    }

    @Test
    void writeSkillRejectsPathTraversal() {
        Path projectDir = tempDir.resolve("my-project");
        assertThrows(IllegalArgumentException.class, () -> SkillReader.writeSkill(
                "../etc", "content", null, null, SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true));
        assertThrows(IllegalArgumentException.class, () -> SkillReader.writeSkill(
                "foo/bar", "content", null, null, SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true));
        assertThrows(IllegalArgumentException.class, () -> SkillReader.writeSkill(
                "foo\\bar", "content", null, null, SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true));
        assertThrows(IllegalArgumentException.class, () -> SkillReader.writeSkill(
                null, "content", null, null, SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true));
    }

    @Test
    void writeSkillEscapesQuotesInDescription() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Patterns",
                "A \"quoted\" description",
                null,
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true);

        String content = Files.readString(written);
        assertTrue(content.contains("description: \"A \\\"quoted\\\" description\""));
    }

    // --- Metadata-only (lazy content) tests ---

    @Test
    void parseFrontmatterMetadataOnlyReturnsNullContent() {
        String content = """
                ---
                name: quarkus-rest
                description: "REST extension"
                mode: enhance
                ---

                ### REST Endpoints
                Use @Path and @GET for endpoints.
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content, true);

        assertEquals("quarkus-rest", info.name());
        assertEquals("REST extension", info.description());
        assertNull(info.content());
        assertEquals(SkillReader.SkillMode.ENHANCE, info.mode());
    }

    @Test
    void readSkillsFromJarMetadataOnlyReturnsNullContent() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    ---

                    ### REST Endpoints
                    Use @Path.
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        List<SkillReader.SkillInfo> skills = SkillReader.readSkillsFromJar(jarPath, true);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("REST extension", skills.get(0).description());
        assertNull(skills.get(0).content());
    }

    @Test
    void readLocalSkillsMetadataOnlyReturnsNullContent() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Path restDir = skillsDir.resolve("quarkus-rest");
        Files.createDirectories(restDir);
        Files.writeString(restDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Local REST skill"
                ---

                ### Local REST
                Local content.
                """);

        List<SkillReader.SkillInfo> skills = SkillReader.readLocalSkills(skillsDir, true);

        assertEquals(1, skills.size());
        assertEquals("quarkus-rest", skills.get(0).name());
        assertEquals("Local REST skill", skills.get(0).description());
        assertNull(skills.get(0).content());
    }

    @Test
    void enhanceModeMetadataOnlyMergesDescriptionNotContent() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "Base description"
                    ---

                    ### Base content
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                description: "Enhanced description"
                mode: enhance
                ---

                ### Enhanced content
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath, true);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"), true);
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        SkillReader.SkillInfo result = skillMap.get("quarkus-rest");
        assertNotNull(result);
        assertEquals("Enhanced description", result.description());
        assertNull(result.content());
    }

    @Test
    void skillModeFromStringDefaultsToEnhance() {
        assertEquals(SkillReader.SkillMode.ENHANCE, SkillReader.SkillMode.fromString(null));
        assertEquals(SkillReader.SkillMode.ENHANCE, SkillReader.SkillMode.fromString("enhance"));
        assertEquals(SkillReader.SkillMode.ENHANCE, SkillReader.SkillMode.fromString("ENHANCE"));
        assertEquals(SkillReader.SkillMode.ENHANCE, SkillReader.SkillMode.fromString("anything-else"));
        assertEquals(SkillReader.SkillMode.OVERRIDE, SkillReader.SkillMode.fromString("override"));
        assertEquals(SkillReader.SkillMode.OVERRIDE, SkillReader.SkillMode.fromString("OVERRIDE"));
    }

    // --- Categories tests ---

    @Test
    void parseFrontmatterExtractsCategories() {
        String content = """
                ---
                name: quarkus-rest
                description: "REST extension"
                categories: "web, reactive"
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals("quarkus-rest", info.name());
        assertEquals(List.of("web", "reactive"), info.categories());
    }

    @Test
    void parseFrontmatterExtractsSingleCategory() {
        String content = """
                ---
                name: quarkus-rest
                category: web
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(List.of("web"), info.categories());
    }

    @Test
    void parseFrontmatterExtractsCategoriesUnquoted() {
        String content = """
                ---
                name: quarkus-rest
                categories: web
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(List.of("web"), info.categories());
    }

    @Test
    void parseFrontmatterReturnsNullCategoriesWhenMissing() {
        String content = """
                ---
                name: quarkus-rest
                description: "REST extension"
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertNull(info.categories());
    }

    @Test
    void enhanceModeMergesCategories() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    description: "REST extension"
                    categories: "web, reactive"
                    ---

                    ### Base REST
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                mode: enhance
                ---

                ### Extra patterns
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        assertEquals(List.of("web", "reactive"), skillMap.get("quarkus-rest").categories());
    }

    @Test
    void enhanceModeOverlayCanOverrideCategories() throws Exception {
        Path jarPath = tempDir.resolve("skills.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("META-INF/skills/quarkus-rest/SKILL.md"));
            jos.write("""
                    ---
                    name: quarkus-rest
                    categories: "web"
                    ---

                    ### Base REST
                    """.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        Path skillsDir = tempDir.resolve("local-skills/quarkus-rest");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), """
                ---
                name: quarkus-rest
                categories: "messaging, reactive"
                mode: enhance
                ---

                ### Extra patterns
                """);

        List<SkillReader.SkillInfo> base = SkillReader.readSkillsFromJar(jarPath);
        java.util.Map<String, SkillReader.SkillInfo> skillMap = new java.util.LinkedHashMap<>();
        for (SkillReader.SkillInfo s : base) {
            skillMap.put(s.name(), s);
        }

        List<SkillReader.SkillInfo> local = SkillReader.readLocalSkills(tempDir.resolve("local-skills"));
        SkillReader.overlaySkills(skillMap, local, "local-skills");

        assertEquals(List.of("messaging", "reactive"), skillMap.get("quarkus-rest").categories());
    }

    @Test
    void writeSkillIncludesCategories() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Patterns",
                "REST skill",
                List.of("web", "reactive"),
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true);

        String content = Files.readString(written);
        assertTrue(content.contains("categories: \"web, reactive\""));
    }

    @Test
    void writeSkillOmitsCategoriesWhenNull() throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);

        Path written = SkillReader.writeSkill(
                "quarkus-rest",
                "### Patterns",
                "REST skill",
                null,
                SkillReader.SkillMode.ENHANCE,
                projectDir.toString(), null, true);

        String content = Files.readString(written);
        assertFalse(content.contains("categories:"));
    }

    @Test
    void parseCategoriesHandlesCommaSeparated() {
        assertEquals(List.of("web", "reactive"), SkillReader.parseCategories("web, reactive"));
        assertEquals(List.of("web"), SkillReader.parseCategories("web"));
        assertNull(SkillReader.parseCategories("  "));
    }

    @Test
    void parseCategoriesNormalizesToLowercase() {
        assertEquals(List.of("web", "reactive"), SkillReader.parseCategories("Web, Reactive"));
        assertEquals(List.of("data"), SkillReader.parseCategories("DATA"));
    }

    @Test
    void parseFrontmatterNormalizesCategoriesToLowercase() {
        String content = """
                ---
                name: quarkus-rest
                categories: "Web, Reactive"
                ---

                ### REST
                """;

        SkillReader.SkillInfo info = SkillReader.parseFrontmatter(content);

        assertEquals(List.of("web", "reactive"), info.categories());
    }
}
