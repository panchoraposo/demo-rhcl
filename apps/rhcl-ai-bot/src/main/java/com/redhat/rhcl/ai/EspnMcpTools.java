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

  @Tool(description = "Fetch NBA scoreboard JSON (via in-cluster proxy)")
  public String nba_scoreboard(
      @ToolArg(description = "Optional range YYYYMMDD-YYYYMMDD", defaultValue = "") String dates
  ) {
    try {
      JsonNode sb = espn.nbaScoreboard(dates == null ? "" : dates);
      return json.write(sb);
    } catch (Exception e) {
      throw new ToolCallException("ESPN tool failed: " + String.valueOf(e.getMessage()));
    }
  }

  @Tool(description = "Fetch Premier League (EPL) scoreboard JSON (via in-cluster proxy)")
  public String epl_scoreboard(
      @ToolArg(description = "Optional range YYYYMMDD-YYYYMMDD", defaultValue = "") String dates
  ) {
    try {
      JsonNode sb = espn.eplScoreboard(dates == null ? "" : dates);
      return json.write(sb);
    } catch (Exception e) {
      throw new ToolCallException("ESPN tool failed: " + String.valueOf(e.getMessage()));
    }
  }

  @Tool(description = "Fetch LaLiga scoreboard JSON (via in-cluster proxy)")
  public String laliga_scoreboard(
      @ToolArg(description = "Optional range YYYYMMDD-YYYYMMDD", defaultValue = "") String dates
  ) {
    try {
      JsonNode sb = espn.laligaScoreboard(dates == null ? "" : dates);
      return json.write(sb);
    } catch (Exception e) {
      throw new ToolCallException("ESPN tool failed: " + String.valueOf(e.getMessage()));
    }
  }

  @Tool(description = "Fetch NFL scoreboard JSON (via in-cluster proxy)")
  public String nfl_scoreboard(
      @ToolArg(description = "Optional range YYYYMMDD-YYYYMMDD", defaultValue = "") String dates
  ) {
    try {
      JsonNode sb = espn.nflScoreboard(dates == null ? "" : dates);
      return json.write(sb);
    } catch (Exception e) {
      throw new ToolCallException("ESPN tool failed: " + String.valueOf(e.getMessage()));
    }
  }

  @Tool(description = "Fetch NHL scoreboard JSON (via in-cluster proxy)")
  public String nhl_scoreboard(
      @ToolArg(description = "Optional range YYYYMMDD-YYYYMMDD", defaultValue = "") String dates
  ) {
    try {
      JsonNode sb = espn.nhlScoreboard(dates == null ? "" : dates);
      return json.write(sb);
    } catch (Exception e) {
      throw new ToolCallException("ESPN tool failed: " + String.valueOf(e.getMessage()));
    }
  }
}

