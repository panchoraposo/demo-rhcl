package com.redhat.rhcl.ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;

import jakarta.inject.Inject;

public class EspnMcpTools {

  @Inject
  EspnTool espn;

  @Inject
  JsonUtil json;

  @Tool(description = "Fetch ESPN NBA scoreboard JSON (via in-cluster proxy)")
  public String espn_nba_scoreboard(
      @ToolArg(description = "Optional range YYYYMMDD-YYYYMMDD", defaultValue = "") String dates
  ) {
    try {
      JsonNode sb = espn.nbaScoreboard(dates == null ? "" : dates);
      return json.write(sb);
    } catch (Exception e) {
      throw new ToolCallException("ESPN tool failed: " + String.valueOf(e.getMessage()));
    }
  }
}

