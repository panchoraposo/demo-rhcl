package com.redhat.rhcl.ai;

import com.fasterxml.jackson.databind.JsonNode;
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
public class EspnTool {
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  @ConfigProperty(name = "rhcl.ai.espn.base-url")
  String baseUrl;

  private final JsonUtil json;

  public EspnTool(JsonUtil json) {
    this.json = json;
  }

  public JsonNode nbaScoreboard(String datesRange) throws Exception {
    // Dates range example: 20260420-20260502
    String qs = (datesRange == null || datesRange.isBlank()) ? "" : ("?dates=" + encode(datesRange));
    URI uri = URI.create(baseUrl + "/external/nba" + qs);
    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(15))
        .GET()
        .header("Accept", "application/json")
        .build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() != 200) {
      throw new RuntimeException("ESPN proxy error status=" + res.statusCode() + " body=" + safe(res.body()));
    }
    return json.readTree(res.body());
  }

  private static String encode(String s) {
    return s.replace(" ", "%20");
  }

  private static String safe(String s) {
    if (s == null) return "";
    return s.length() > 600 ? s.substring(0, 600) : s;
  }
}

