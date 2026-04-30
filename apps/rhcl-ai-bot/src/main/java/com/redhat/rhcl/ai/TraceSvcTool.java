package com.redhat.rhcl.ai;

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
public class TraceSvcTool {
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  @ConfigProperty(name = "rhcl.ai.trace.base-url")
  String baseUrl;

  @Inject
  OpenTelemetry otel;

  private static final TextMapSetter<HttpRequest.Builder> REQ_SETTER = (carrier, key, value) -> {
    if (carrier != null && key != null && value != null) {
      carrier.header(key, value);
    }
  };

  public int ping() throws Exception {
    Tracer tracer = otel.getTracer("rhcl-ai-bot");
    Span span = tracer.spanBuilder("trace_svc.ping")
        .setSpanKind(SpanKind.CLIENT)
        .startSpan();
    try (Scope scope = span.makeCurrent()) {
      URI uri = URI.create(baseUrl + "/trace/ping");
      HttpRequest.Builder b = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(10))
          .GET()
          .header("Accept", "application/json");

      otel.getPropagators().getTextMapPropagator().inject(io.opentelemetry.context.Context.current(), b, REQ_SETTER);

      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      span.setAttribute("http.status_code", res.statusCode());
      return res.statusCode();
    } catch (Exception e) {
      span.recordException(e);
      span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
      throw e;
    } finally {
      span.end();
    }
  }
}

