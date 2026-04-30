package com.redhat.rhcl.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/trace")
@Produces(MediaType.APPLICATION_JSON)
public class TraceResource {
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  @ConfigProperty(name = "rhcl.trace.external-proxy.base-url")
  String externalProxyBaseUrl;

  @Inject
  OpenTelemetry otel;

  private static final TextMapSetter<HttpRequest.Builder> REQ_SETTER = (carrier, key, value) -> {
    if (carrier != null && key != null && value != null) {
      carrier.header(key, value);
    }
  };

  @GET
  @Path("/ping")
  public Response ping() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("service", "rhcl-trace-svc");
    out.put("ts", Instant.now().toString());

    int downstreamStatus = -1;
    String downstreamError = "";
    try {
      downstreamStatus = callExternalProxy();
    } catch (Exception e) {
      downstreamError = String.valueOf(e.getMessage());
    }

    out.put("external_proxy_status", downstreamStatus);
    if (!downstreamError.isBlank()) {
      out.put("external_proxy_error", downstreamError);
    }
    return Response.ok(out).build();
  }

  private int callExternalProxy() throws Exception {
    Tracer tracer = otel.getTracer("rhcl-trace-svc");
    Span span = tracer.spanBuilder("external_proxy.nbaScoreboard")
        .setSpanKind(SpanKind.CLIENT)
        .startSpan();
    try (Scope scope = span.makeCurrent()) {
      URI uri = URI.create(externalProxyBaseUrl + "/external/nba");
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

