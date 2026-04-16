package io.quarkus.agent.mcp;

import java.util.Map;

import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * MCP tools for managing Quarkus application lifecycle.
 * These tools allow AI coding agents to start, stop, restart,
 * and monitor Quarkus applications running in dev mode.
 */
public class LifecycleTools {

    @Inject
    QuarkusProcessManager processManager;

    @Inject
    ObjectMapper mapper;

    @Tool(name = "quarkus/start", description = "Start a Quarkus application in dev mode. "
            + "Auto-detects Maven or Gradle. Hot reload is triggered when the app is accessed. "
            + "RULES: Always write tests. Always keep README.md updated after changes.")
    ToolResponse start(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Build tool to use: 'maven' or 'gradle' (auto-detected if omitted)", required = false) String buildTool) {
        try {
            processManager.start(projectDir, buildTool);
            return ToolResponse.success("Quarkus application starting in dev mode at: " + projectDir);
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/stop", description = "Stop a running Quarkus application. "
            + "Sends a graceful shutdown signal, then force-kills if needed.")
    ToolResponse stop(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            processManager.stop(projectDir);
            return ToolResponse.success("Quarkus application stopped at: " + projectDir);
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/restart", description = "Force restart a Quarkus application. "
            + "Only use if the app is unresponsive. Normally hot reload handles changes automatically.")
    ToolResponse restart(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            processManager.restart(projectDir);
            return ToolResponse.success("Quarkus application restart triggered at: " + projectDir);
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/status", description = "Get the status of a Quarkus application. "
            + "Returns: not_started, starting, running (with port), crashed, or stopped.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse status(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir) {
        try {
            QuarkusInstance instance = processManager.getInstance(projectDir);
            if (instance == null) {
                return ToolResponse.success("not_started");
            }
            String status = instance.getStatus().name().toLowerCase();
            if (instance.getStatus() == QuarkusInstance.Status.RUNNING && instance.getHttpPort() > 0) {
                return ToolResponse.success(status + " (port: " + instance.getHttpPort() + ")");
            }
            return ToolResponse.success(status);
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/logs", description = "Get recent log output from a managed Quarkus application. "
            + "For structured exception details (class, message, stack trace, user code location), "
            + "prefer quarkus/callTool with toolName 'devui-exceptions_getLastException' instead.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    ToolResponse logs(
            @ToolArg(description = "Absolute path to the Quarkus project directory") String projectDir,
            @ToolArg(description = "Number of recent lines to return (default: 50)", required = false) Integer lines) {
        try {
            QuarkusInstance instance = processManager.getInstance(projectDir);
            if (instance == null) {
                return ToolResponse.error("No instance found for: " + projectDir);
            }
            int count = (lines != null && lines > 0) ? Math.min(lines, 10000) : 50;
            return ToolResponse.success(instance.getRecentLogs(count));
        } catch (Exception e) {
            return ToolResponse.error(e.getMessage());
        }
    }

    @Tool(name = "quarkus/list", description = "List all managed Quarkus application instances and their current status.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse list() {
        try {
            Map<String, String> instances = processManager.listInstances();
            if (instances.isEmpty()) {
                return ToolResponse.success("No managed Quarkus instances");
            }
            return ToolResponse.success(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instances));
        } catch (JsonProcessingException e) {
            return ToolResponse.error("Failed to serialize instance list: " + e.getMessage());
        }
    }
}
