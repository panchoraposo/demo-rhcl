package com.redhat.rhcl.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpResource {
  private final JsonUtil json;
  private final EspnTool espn;
  private final ToolDefinition espnNbaMeta;

  public McpResource(JsonUtil json, EspnTool espn) {
    this.json = json;
    this.espn = espn;
    this.espnNbaMeta = resolveToolMeta(espn, "nbaScoreboard");
  }

  @GET
  public ObjectNode info() {
    ObjectNode o = json.obj();
    o.put("name", "rhcl-ai-bot");
    o.put("protocol", "mcp-like");
    o.put("tools_list", "/mcp/tools/list");
    o.put("tools_call", "/mcp/tools/call");
    return o;
  }

  @GET
  @Path("/tools/list")
  public ObjectNode listTools() {
    ObjectNode out = json.obj();
    ArrayNode tools = out.putArray("tools");

    ObjectNode t = json.obj();
    t.put("name", espnNbaMeta.name());
    t.put("description", espnNbaMeta.description());
    ObjectNode schema = json.obj();
    schema.put("type", "object");
    ObjectNode props = json.obj();
    ObjectNode dates = json.obj();
    dates.put("type", "string");
    dates.put("description", "Optional range YYYYMMDD-YYYYMMDD");
    props.set("dates", dates);
    schema.set("properties", props);
    t.set("inputSchema", schema);
    tools.add(t);

    return out;
  }

  @POST
  @Path("/tools/call")
  public Response callTool(JsonNode req) {
    try {
      String name = req.path("name").asText("");
      JsonNode args = req.path("arguments");
      if (!espnNbaMeta.name().equals(name)) {
        ObjectNode err = json.obj();
        err.put("error", "unknown_tool");
        err.put("name", name);
        return Response.status(400).entity(err).build();
      }
      String dates = args.path("dates").asText("");
      JsonNode sb = espn.nbaScoreboard(dates);
      ObjectNode out = json.obj();
      out.put("tool", name);
      out.set("result", sb);
      return Response.ok(out).build();
    } catch (Exception e) {
      ObjectNode err = json.obj();
      err.put("error", "tool_call_failed");
      err.put("message", String.valueOf(e.getMessage()));
      return Response.status(500).entity(err).build();
    }
  }

  private static ToolDefinition resolveToolMeta(Object instance, String methodName) {
    try {
      var m = instance.getClass().getMethod(methodName, String.class);
      var ann = m.getAnnotation(ToolDefinition.class);
      if (ann == null) throw new IllegalStateException("Missing @ToolDefinition on " + instance.getClass().getName() + "#" + methodName);
      return ann;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to resolve tool metadata for " + instance.getClass().getName() + "#" + methodName, e);
    }
  }
}

