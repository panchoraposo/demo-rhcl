package com.redhat.rhcl.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/ai/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpProxyResource {
  private final JsonUtil json;
  private final McpGatewayClient mcp;

  public McpProxyResource(JsonUtil json, McpGatewayClient mcp) {
    this.json = json;
    this.mcp = mcp;
  }

  @POST
  @Path("/ping")
  public Response ping() {
    ObjectNode out = json.obj();
    try {
      if (mcp == null || !mcp.enabled()) {
        out.put("ok", false);
        out.put("enabled", false);
        out.put("message", "MCP Gateway is disabled (missing rhcl.ai.mcp.base-url)");
        return Response.status(503).entity(out).build();
      }
      mcp.ping();
      out.put("ok", true);
      out.put("enabled", true);
      return Response.ok(out).build();
    } catch (Exception e) {
      out.put("ok", false);
      out.put("enabled", mcp != null && mcp.enabled());
      out.put("message", String.valueOf(e.getMessage()));
      return Response.status(502).entity(out).build();
    }
  }

  @POST
  @Path("/tools/list")
  public Response listTools() {
    try {
      if (mcp == null || !mcp.enabled()) {
        ObjectNode out = json.obj();
        out.put("error", "mcp_disabled");
        out.put("message", "MCP Gateway is disabled (missing rhcl.ai.mcp.base-url)");
        return Response.status(503).entity(out).build();
      }
      JsonNode res = mcp.listToolsJson();
      return Response.ok(res).build();
    } catch (Exception e) {
      ObjectNode out = json.obj();
      out.put("error", "tools_list_failed");
      out.put("message", String.valueOf(e.getMessage()));
      return Response.status(502).entity(out).build();
    }
  }

  @POST
  @Path("/tools/call")
  public Response callTool(JsonNode req) {
    try {
      if (mcp == null || !mcp.enabled()) {
        ObjectNode out = json.obj();
        out.put("error", "mcp_disabled");
        out.put("message", "MCP Gateway is disabled (missing rhcl.ai.mcp.base-url)");
        return Response.status(503).entity(out).build();
      }
      String name = req == null ? "" : req.path("name").asText("");
      JsonNode argsNode = req == null ? null : req.get("arguments");
      ObjectNode args = (argsNode != null && argsNode.isObject()) ? (ObjectNode) argsNode : json.obj();
      JsonNode res = mcp.callToolJson(name, args);
      return Response.ok(res).build();
    } catch (Exception e) {
      ObjectNode out = json.obj();
      out.put("error", "tools_call_failed");
      out.put("message", String.valueOf(e.getMessage()));
      return Response.status(502).entity(out).build();
    }
  }
}

