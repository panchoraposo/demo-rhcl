package com.redhat.rhcl.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Path("/ai/v1/chat/completions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {
  private final JsonUtil json;
  private final EspnTool espn;
  private final TraceSvcTool traceSvc;
  private final OpenAiClient llm;
  private final McpGatewayClient mcp;

  public ChatResource(JsonUtil json, EspnTool espn, TraceSvcTool traceSvc, OpenAiClient llm, McpGatewayClient mcp) {
    this.json = json;
    this.espn = espn;
    this.traceSvc = traceSvc;
    this.llm = llm;
    this.mcp = mcp;
  }

  @POST
  public Response chat(JsonNode req) {
    try {
      String model = req.path("model").asText("rhcl-ai-bot");
      ArrayNode messages = req.has("messages") && req.get("messages").isArray() ? (ArrayNode) req.get("messages") : json.mapper().createArrayNode();
      String userText = lastUserMessage(messages);

      String answer;
      String toolNote = "";
      String mcpError = "";
      String llmError = "";

      if (looksLikeScoreboardQuestion(userText)) {
        League league = leagueFromUserText(userText);
        String dates = extractDatesRange(userText);
        JsonNode sb;
        boolean detailed = wantsDetailed(userText);
        int limit = extractLimit(userText);
        try {
          if (mcp != null && mcp.enabled()) {
            ObjectNode args = json.obj();
            if (dates != null && !dates.isBlank()) args.put("dates", dates);
            String toolName = extractToolName(userText);
            if (toolName.isBlank()) toolName = mcp.scoreboardToolName();
            String txt = mcp.callToolText(toolName, args);
            sb = json.readTree(txt);
            toolNote = "tool=" + toolName + " via=mcp-gateway league=" + league.label;
          } else {
            sb = directScoreboard(league, dates);
            toolNote = "tool=espn_scoreboard via=direct league=" + league.label;
          }
        } catch (Exception e) {
          sb = directScoreboard(league, dates);
          toolNote = "tool=espn_scoreboard via=direct league=" + league.label;
          mcpError = safe(String.valueOf(e.getMessage()));
        }

        String summary = summarizeScoreboard(sb, league, detailed, limit);

        try {
          int st = traceSvc.ping();
          toolNote = toolNote + " trace_svc_status=" + st;
        } catch (Exception ignored) {
          toolNote = toolNote + " trace_svc_status=error";
        }

        boolean toolOnly = wantsToolOnly(userText);
        if (llm.enabled() && !toolOnly) {
          try {
            ArrayNode prompt = json.mapper().createArrayNode();
            String style = detailed ? "Be detailed and structured." : "Be concise.";
            prompt.add(obj("system",
                "You are a helpful sports assistant for a workshop demo. Use the provided scoreboard summary to answer the user question. " +
                style +
                " Do not include internal reasoning. Output MUST start with 'FINAL:' and nothing else before it."));
            prompt.add(obj("user", "User question: " + safe(userText) + "\n\n" + league.displayName + " scoreboard summary:\n" + summary));
            JsonNode llmRes = llm.chat(prompt);
            answer = llmRes.at("/choices/0/message/content").asText("");
            answer = sanitizeAnswer(answer);
            if (answer.isBlank()) {
              answer = summary;
              llmError = "LLM returned an empty answer";
            }
          } catch (Exception e) {
            answer = summary;
            llmError = String.valueOf(e.getMessage());
          }
        } else {
          answer = summary;
        }
      } else {
        answer = "Ask me about sports scoreboards (NBA, Premier League, LaLiga, NFL, NHL). Example: \"Show finals in the last 7 days\".";
      }

      int promptTokens = TokenEstimator.estimateTokens(userText);
      int completionTokens = TokenEstimator.estimateTokens(answer);
      int totalTokens = promptTokens + completionTokens;

      ObjectNode out = json.obj();
      out.put("id", "chatcmpl-" + UUID.randomUUID());
      out.put("object", "chat.completion");
      out.put("created", Instant.now().getEpochSecond());
      out.put("model", model);

      ArrayNode choices = out.putArray("choices");
      ObjectNode choice = json.obj();
      choice.put("index", 0);
      ObjectNode msg = json.obj();
      msg.put("role", "assistant");
      msg.put("content", sanitizeAnswer(answer));
      choice.set("message", msg);
      choice.put("finish_reason", "stop");
      choices.add(choice);

      ObjectNode usage = json.obj();
      usage.put("prompt_tokens", promptTokens);
      usage.put("completion_tokens", completionTokens);
      usage.put("total_tokens", totalTokens);
      out.set("usage", usage);

      ObjectNode meta = json.obj();
      meta.put("tool", toolNote);
      meta.put("llm_enabled", llm.enabled());
      if (!mcpError.isBlank()) {
        meta.put("mcp_error", mcpError);
      }
      if (!llmError.isBlank()) {
        meta.put("llm_error", llmError);
      }
      out.set("rhcl_meta", meta);

      return Response.ok(out).build();
    } catch (Exception e) {
      ObjectNode err = json.obj();
      err.put("error", "chat_failed");
      err.put("message", String.valueOf(e.getMessage()));
      return Response.status(500).entity(err).build();
    }
  }

  private ObjectNode obj(String role, String content) {
    ObjectNode o = json.obj();
    o.put("role", role);
    o.put("content", content);
    return o;
  }

  private static String lastUserMessage(ArrayNode messages) {
    if (messages == null) return "";
    for (int i = messages.size() - 1; i >= 0; i--) {
      JsonNode m = messages.get(i);
      if ("user".equalsIgnoreCase(m.path("role").asText())) {
        return m.path("content").asText("");
      }
    }
    return "";
  }

  private static boolean looksLikeScoreboardQuestion(String t) {
    String s = (t == null ? "" : t).toLowerCase();
    return s.contains("nba")
        || s.contains("epl")
        || s.contains("premier league")
        || s.contains("la liga")
        || s.contains("laliga")
        || s.contains("nfl")
        || s.contains("nhl")
        || s.contains("game")
        || s.contains("games")
        || s.contains("score")
        || s.contains("scoreboard")
        || s.contains("live")
        || s.contains("final")
        || s.contains("tool=");
  }

  private static String extractDatesRange(String t) {
    // Accept "dates=YYYYMMDD-YYYYMMDD" (or comma list) if user provides it; otherwise omit.
    String s = t == null ? "" : t;
    int idx = s.indexOf("dates=");
    if (idx < 0) return "";
    String rest = s.substring(idx + 6).trim();
    int end = -1;
    for (int i = 0; i < rest.length(); i++) {
      if (Character.isWhitespace(rest.charAt(i))) {
        end = i;
        break;
      }
    }
    String v = (end >= 0) ? rest.substring(0, end) : rest;
    // Keep only digits, commas and hyphens.
    return v.replaceAll("[^0-9,\\-]", "");
  }

  private String summarizeScoreboard(JsonNode sb, League league, boolean detailed, int limit) {
    JsonNode eventsNode = sb == null ? null : sb.path("events");
    if (eventsNode == null || !eventsNode.isArray() || eventsNode.isEmpty()) {
      return "No " + league.displayName + " events found in the selected window.";
    }
    ArrayNode events = (ArrayNode) eventsNode;
    StringBuilder out = new StringBuilder();
    int cap = detailed ? 28 : 12;
    if (limit > 0) cap = Math.min(cap, limit);
    int n = Math.min(cap, events.size());
    out.append("Found ").append(events.size()).append(" events");
    if (n < events.size()) out.append(" (showing ").append(n).append(")");
    out.append(".\n");
    for (int i = 0; i < n; i++) {
      JsonNode ev = events.get(i);
      JsonNode comp = ev.path("competitions").isArray() && ev.path("competitions").size() > 0 ? ev.path("competitions").get(0) : null;
      String shortDetail = comp != null ? comp.at("/status/type/shortDetail").asText("") : ev.at("/status/type/shortDetail").asText("");
      String date = ev.path("date").asText("");
      JsonNode competitors = comp != null ? comp.path("competitors") : null;
      String away = teamLine(competitors, "away");
      String home = teamLine(competitors, "home");
      String venue = comp != null ? comp.at("/venue/fullName").asText("") : "";
      String headline = comp != null ? comp.at("/headlines/0/shortLinkText").asText("") : "";
      out.append("- ").append(shortDetail.isBlank() ? league.displayName : shortDetail)
          .append(" · ").append(date)
          .append("\n  ").append(away)
          .append("\n  ").append(home);
      if (detailed) {
        if (!venue.isBlank()) out.append("\n  venue: ").append(venue);
        String broadcast = broadcastLine(comp);
        if (!broadcast.isBlank()) out.append("\n  broadcast: ").append(broadcast);
        String series = seriesLine(comp);
        if (!series.isBlank()) out.append("\n  series: ").append(series);
        if (!headline.isBlank()) out.append("\n  headline: ").append(headline);
      }
      out.append("\n");
      if (detailed) {
        String ls = lineScore(competitors);
        if (!ls.isBlank()) out.append("  linescore: ").append(ls).append("\n");
      }
      out.append("\n");
      if (out.length() > 12000) {
        out.append("…(truncated for demo UI)\n");
        break;
      }
    }
    return out.toString().trim();
  }

  private JsonNode directScoreboard(League league, String dates) throws Exception {
    String d = dates == null ? "" : dates;
    return switch (league) {
      case EPL -> espn.eplScoreboard(d);
      case LALIGA -> espn.laligaScoreboard(d);
      case NFL -> espn.nflScoreboard(d);
      case NHL -> espn.nhlScoreboard(d);
      case NBA -> espn.nbaScoreboard(d);
    };
  }

  private static String extractToolName(String t) {
    // Accept "tool=<mcp_tool_name>" (single token).
    String s = t == null ? "" : t;
    int idx = s.indexOf("tool=");
    if (idx < 0) return "";
    String rest = s.substring(idx + 5).trim();
    int end = -1;
    for (int i = 0; i < rest.length(); i++) {
      if (Character.isWhitespace(rest.charAt(i))) { end = i; break; }
    }
    String v = (end >= 0) ? rest.substring(0, end) : rest;
    return v.replaceAll("[^a-zA-Z0-9_\\-]", "");
  }

  private static League leagueFromUserText(String t) {
    String s = t == null ? "" : t.toLowerCase(Locale.ROOT);
    String tool = extractToolName(t).toLowerCase(Locale.ROOT);
    if (tool.contains("epl")) return League.EPL;
    if (tool.contains("laliga")) return League.LALIGA;
    if (tool.contains("nfl")) return League.NFL;
    if (tool.contains("nhl")) return League.NHL;
    if (tool.contains("nba")) return League.NBA;

    if (s.contains("premier league") || s.contains("epl")) return League.EPL;
    if (s.contains("la liga") || s.contains("laliga")) return League.LALIGA;
    if (s.contains("nfl")) return League.NFL;
    if (s.contains("nhl")) return League.NHL;
    return League.NBA;
  }

  enum League {
    NBA("nba", "NBA"),
    EPL("epl", "Premier League"),
    LALIGA("laliga", "LaLiga"),
    NFL("nfl", "NFL"),
    NHL("nhl", "NHL");

    final String label;
    final String displayName;

    League(String label, String displayName) {
      this.label = label;
      this.displayName = displayName;
    }
  }

  private static int extractLimit(String t) {
    // Accept "limit=N" (1..50) to control output size for a stable demo.
    String s = t == null ? "" : t;
    int idx = s.indexOf("limit=");
    if (idx < 0) return 0;
    String rest = s.substring(idx + 6).trim();
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c >= '0' && c <= '9') digits.append(c);
      else break;
    }
    if (digits.isEmpty()) return 0;
    try {
      int n = Integer.parseInt(digits.toString());
      if (n < 1) return 1;
      if (n > 50) return 50;
      return n;
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static boolean wantsDetailed(String t) {
    String s = (t == null ? "" : t).toLowerCase();
    return s.contains("much longer")
        || s.contains("very long")
        || s.contains("very detailed")
        || s.contains("top storylines")
        || s.contains("notable performers")
        || s.contains("appendix")
        || s.contains("table");
  }

  private static boolean wantsToolOnly(String t) {
    String s = (t == null ? "" : t).toLowerCase();
    return s.contains("tool_only=true") || s.contains("tool-only") || s.contains("tool only");
  }

  private static String broadcastLine(JsonNode comp) {
    if (comp == null) return "";
    JsonNode arr = comp.path("broadcasts");
    if (!arr.isArray() || arr.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    int n = Math.min(3, arr.size());
    for (int i = 0; i < n; i++) {
      JsonNode b = arr.get(i);
      String name = b.at("/names/0").asText(b.at("/media/shortName").asText(""));
      if (!name.isBlank()) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(name);
      }
    }
    return sb.toString();
  }

  private static String seriesLine(JsonNode comp) {
    if (comp == null) return "";
    String st = comp.at("/series/summary").asText("");
    if (!st.isBlank()) return st;
    // Some payloads use "type" blocks; keep it minimal.
    return "";
  }

  private static String lineScore(JsonNode competitors) {
    if (competitors == null || !competitors.isArray()) return "";
    StringBuilder out = new StringBuilder();
    for (JsonNode c : competitors) {
      String ha = c.path("homeAway").asText("");
      JsonNode lsNode = c.path("linescores");
      if (lsNode == null || !lsNode.isArray() || lsNode.isEmpty()) continue;
      ArrayNode ls = (ArrayNode) lsNode;
      if (out.length() > 0) out.append(" | ");
      out.append(ha).append(":");
      int n = Math.min(4, ls.size());
      for (int i = 0; i < n; i++) {
        String v = ls.get(i).path("value").asText("");
        if (!v.isBlank()) out.append(" ").append(v);
      }
    }
    return out.toString().trim();
  }

  private static String teamLine(JsonNode competitors, String homeAway) {
    if (competitors == null || !competitors.isArray()) return homeAway + ": (missing)";
    for (JsonNode c : competitors) {
      if (homeAway.equalsIgnoreCase(c.path("homeAway").asText())) {
        String name = c.at("/team/displayName").asText(c.at("/team/name").asText("team"));
        String score = c.path("score").asText("");
        return homeAway + ": " + name + (score.isBlank() ? "" : (" " + score));
      }
    }
    return homeAway + ": (missing)";
  }

  private static String safe(String s) {
    if (s == null) return "";
    return s.length() > 800 ? s.substring(0, 800) : s;
  }

  private static String sanitizeAnswer(String text) {
    if (text == null) return "";
    String s = text;
    s = s.replaceAll("(?s)<think>.*?</think>", "");
    s = s.replace("<think>", "").replace("</think>", "");
    String lower = s.toLowerCase(Locale.ROOT);
    int idx = lower.indexOf("final:");
    if (idx >= 0) {
      s = s.substring(idx + "final:".length());
    }
    return s.trim();
  }
}

