package com.redhat.rhcl.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
public class OpenAiClient {
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private final JsonUtil json;

  @ConfigProperty(name = "rhcl.ai.openai.base-url", defaultValue = "https://api.openai.com/v1")
  String baseUrl;

  @ConfigProperty(name = "rhcl.ai.openai.api-key", defaultValue = "")
  String apiKey;

  @ConfigProperty(name = "rhcl.ai.openai.model", defaultValue = "gpt-4o-mini")
  String model;

  public OpenAiClient(JsonUtil json) {
    this.json = json;
  }

  public boolean enabled() {
    return apiKey != null && !apiKey.isBlank();
  }

  public JsonNode chat(ArrayNode messages) throws Exception {
    ObjectNode body = json.obj();
    body.put("model", model);
    body.set("messages", messages);
    body.put("temperature", 0.2);

    URI uri = URI.create(stripTrailingSlash(baseUrl) + "/chat/completions");
    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(json.write(body)))
        .build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      throw new RuntimeException("LLM error status=" + res.statusCode() + " body=" + safe(res.body()));
    }
    return json.readTree(res.body());
  }

  private static String stripTrailingSlash(String s) {
    if (s == null) return "";
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String safe(String s) {
    if (s == null) return "";
    return s.length() > 800 ? s.substring(0, 800) : s;
  }
}

