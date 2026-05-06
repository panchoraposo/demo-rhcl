package com.redhat.rhcl.ai;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;

/**
 * Some MCP clients (including gateway components) send {@code Accept: application/json}
 * to the streamable HTTP MCP endpoint. Quarkiverse MCP server validates the Accept header
 * and expects the SSE token to be present. We normalize the header to keep the demo stable.
 */
public class McpAcceptHeaderFilter {

  @RouteFilter(100)
  void normalizeMcpAcceptHeader(RoutingContext ctx) {
    String path = ctx.request().path() == null ? "" : ctx.request().path();
    if (!path.startsWith("/mcp")) {
      ctx.next();
      return;
    }

    String accept = ctx.request().getHeader("accept");
    if (accept == null || accept.isBlank()) {
      ctx.request().headers().set("accept", "application/json, text/event-stream");
      ctx.next();
      return;
    }

    String a = accept.toLowerCase();
    if (a.contains("text/event-stream")) {
      ctx.next();
      return;
    }

    // Keep any existing tokens, just ensure the SSE token is present.
    ctx.request().headers().set("accept", accept + ", text/event-stream");
    ctx.next();
  }
}

