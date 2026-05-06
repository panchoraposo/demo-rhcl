package com.redhat.rhcl.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
@Unremovable
public class McpGatewayClient {
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private final JsonUtil json;

  // NOTE: Quarkus/MicroProfile config can treat an empty-string default as "unset" in some cases.
  // Use an explicit sentinel so deployments that don't configure MCP can still start.
  @ConfigProperty(name = "rhcl.ai.mcp.base-url", defaultValue = "__disabled__")
  String baseUrl;

  @ConfigProperty(name = "rhcl.ai.mcp.path", defaultValue = "/mcp")
  String path;

  @ConfigProperty(name = "rhcl.ai.mcp.timeout-seconds", defaultValue = "10")
  int timeoutSeconds;

  @ConfigProperty(name = "rhcl.ai.mcp.tool-scoreboard", defaultValue = "nba_scoreboard")
  String scoreboardToolName;

  public McpGatewayClient(JsonUtil json) {
    this.json = json;
  }

  public boolean enabled() {
    String v = baseUrl == null ? "" : baseUrl.trim();
    return !v.isBlank() && !v.equals("__disabled__");
  }

  public String scoreboardToolName() {
    return scoreboardToolName == null ? "" : scoreboardToolName.trim();
  }

  public JsonNode callToolJson(String toolName, ObjectNode arguments) throws Exception {
    if (!enabled()) {
      throw new IllegalStateException("MCP Gateway disabled: missing rhcl.ai.mcp.base-url");
    }
    String sessionId = initialize();
    try {
      return toolsCall(sessionId, toolName, arguments);
    } finally {
      // No explicit session close required for demo; the gateway expires sessions.
    }
  }

  public String callToolText(String toolName, ObjectNode arguments) throws Exception {
    JsonNode res = callToolJson(toolName, arguments);
    String text = res.at("/result/content/0/text").asText("");
    if (!text.isBlank()) return text;
    // Fallback if gateway/server returns a different shape.
    text = res.at("/result/text").asText("");
    if (!text.isBlank()) return text;
    return res.toString();
  }

  private String initialize() throws Exception {
    ObjectNode p = json.obj();
    p.put("protocolVersion", "2025-11-25");
    p.set("capabilities", json.obj());
    ObjectNode ci = json.obj();
    ci.put("name", "rhcl-ai-bot");
    ci.put("version", "1");
    p.set("clientInfo", ci);

    ObjectNode req = json.obj();
    req.put("jsonrpc", "2.0");
    req.put("id", 1);
    req.put("method", "initialize");
    req.set("params", p);

    HttpResponse<String> res = post(null, req);
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      throw new RuntimeException("MCP initialize failed status=" + res.statusCode() + " body=" + safe(res.body()));
    }
    String sid = header(res, "mcp-session-id");
    if (sid.isBlank()) {
      throw new RuntimeException("MCP initialize failed: missing mcp-session-id header, status=" + res.statusCode());
    }
    // MCP requires a notifications/initialized after initialize.
    // Some gateways invalidate sessions if this is missing.
    notifyInitialized(sid);
    return sid;
  }

  private void notifyInitialized(String sessionId) throws Exception {
    ObjectNode req = json.obj();
    req.put("jsonrpc", "2.0");
    req.put("method", "notifications/initialized");
    HttpResponse<String> res = post(sessionId, req);
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      throw new RuntimeException("MCP notifications/initialized failed status=" + res.statusCode() + " body=" + safe(res.body()));
    }
  }

  private JsonNode toolsCall(String sessionId, String toolName, ObjectNode arguments) throws Exception {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("MCP tools/call requires a tool name");
    }
    ObjectNode params = json.obj();
    params.put("name", toolName);
    params.set("arguments", arguments == null ? json.obj() : arguments);

    ObjectNode req = json.obj();
    req.put("jsonrpc", "2.0");
    req.put("id", 2);
    req.put("method", "tools/call");
    req.set("params", params);

    HttpResponse<String> res = post(sessionId, req);
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      throw new RuntimeException("MCP tools/call failed status=" + res.statusCode() + " body=" + safe(res.body()));
    }
    return json.readTree(res.body());
  }

  private HttpResponse<String> post(String sessionId, ObjectNode body) throws Exception {
    URI uri = URI.create(stripTrailingSlash(baseUrl) + stripLeadingSlash(path));
    HttpRequest.Builder b = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream");
    if (sessionId != null && !sessionId.isBlank()) {
      b.header("mcp-session-id", sessionId);
    }
    HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(json.write(body))).build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private static String stripTrailingSlash(String s) {
    if (s == null) return "";
    String v = s.trim();
    return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
  }

  private static String stripLeadingSlash(String s) {
    if (s == null || s.isBlank()) return "/mcp";
    String v = s.trim();
    return v.startsWith("/") ? v : ("/" + v);
  }

  private static String safe(String s) {
    if (s == null) return "";
    return s.length() > 800 ? s.substring(0, 800) : s;
  }

  private static String header(HttpResponse<?> res, String name) {
    if (res == null || name == null) return "";
    return res.headers().firstValue(name).orElse("").trim();
  }
}

