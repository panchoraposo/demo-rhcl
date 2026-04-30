package com.redhat.rhcl.ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.Unremovable;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

  @Inject
  OpenTelemetry otel;

  private static final TextMapSetter<HttpRequest.Builder> REQ_SETTER = (carrier, key, value) -> {
    if (carrier != null && key != null && value != null) {
      carrier.header(key, value);
    }
  };

  public EspnTool(JsonUtil json) {
    this.json = json;
  }

  public JsonNode nbaScoreboard(String datesRange) throws Exception {
    // Dates range example: 20260420-20260502
    String qs = (datesRange == null || datesRange.isBlank()) ? "" : ("?dates=" + encode(datesRange));
    URI uri = URI.create(baseUrl + "/external/nba" + qs);
    Tracer tracer = otel.getTracer("rhcl-ai-bot");
    Span span = tracer.spanBuilder("espn.nbaScoreboard")
        .setSpanKind(SpanKind.CLIENT)
        .startSpan();
    try (Scope scope = span.makeCurrent()) {
      HttpRequest.Builder b = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(15))
          .GET()
          .header("Accept", "application/json");

      otel.getPropagators().getTextMapPropagator().inject(io.opentelemetry.context.Context.current(), b, REQ_SETTER);

      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      span.setAttribute("http.status_code", res.statusCode());
      if (res.statusCode() != 200) {
        throw new RuntimeException("ESPN proxy error status=" + res.statusCode() + " body=" + safe(res.body()));
      }
      return json.readTree(res.body());
    } catch (Exception e) {
      span.recordException(e);
      span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
      throw e;
    } finally {
      span.end();
    }
  }

  private static String encode(String s) {
    return s.replace(" ", "%20");
  }

  private static String safe(String s) {
    if (s == null) return "";
    return s.length() > 600 ? s.substring(0, 600) : s;
  }
}

