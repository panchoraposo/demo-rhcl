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
import java.util.UUID;

@Path("/ai/v1/chat/completions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {
  private final JsonUtil json;
  private final EspnTool espn;
  private final OpenAiClient llm;

  public ChatResource(JsonUtil json, EspnTool espn, OpenAiClient llm) {
    this.json = json;
    this.espn = espn;
    this.llm = llm;
  }

  @POST
  public Response chat(JsonNode req) {
    try {
      String model = req.path("model").asText("rhcl-ai-bot");
      ArrayNode messages = req.has("messages") && req.get("messages").isArray() ? (ArrayNode) req.get("messages") : json.mapper().createArrayNode();
      String userText = lastUserMessage(messages);

      String answer;
      String toolNote = "";

      if (looksLikeScoreboardQuestion(userText)) {
        String dates = extractDatesRange(userText);
        JsonNode sb = espn.nbaScoreboard(dates);
        String summary = summarizeNba(sb);
        toolNote = "tool=espn_nba_scoreboard";

        if (llm.enabled()) {
          ArrayNode prompt = json.mapper().createArrayNode();
          prompt.add(obj("system", "You are a helpful sports assistant for a workshop demo. Use the provided NBA scoreboard summary to answer the user question. Be concise."));
          prompt.add(obj("user", "User question: " + safe(userText) + "\n\nNBA scoreboard summary:\n" + summary));
          JsonNode llmRes = llm.chat(prompt);
          answer = llmRes.at("/choices/0/message/content").asText("");
          if (answer.isBlank()) {
            answer = "I could not generate an answer from the LLM response. Here is the scoreboard summary:\n" + summary;
          }
        } else {
          answer = summary;
        }
      } else {
        answer = "Ask me about NBA games (for example: \"What games are live today?\" or \"Show me finals in the last 7 days\").";
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
      msg.put("content", answer);
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
    return s.contains("nba") || s.contains("game") || s.contains("games") || s.contains("score") || s.contains("live") || s.contains("final");
  }

  private static String extractDatesRange(String t) {
    // Accept "dates=YYYYMMDD-YYYYMMDD" if user provides it; otherwise omit.
    String s = t == null ? "" : t;
    int idx = s.indexOf("dates=");
    if (idx < 0) return "";
    String rest = s.substring(idx + 6).trim();
    int end = rest.indexOf(' ');
    String v = (end >= 0) ? rest.substring(0, end) : rest;
    return v.replaceAll("[^0-9\\-]", "");
  }

  private String summarizeNba(JsonNode sb) {
    ArrayNode events = (ArrayNode) sb.path("events");
    if (events == null || events.isEmpty()) {
      return "No NBA events found in the selected window.";
    }
    StringBuilder out = new StringBuilder();
    out.append("Found ").append(events.size()).append(" events.\n");
    int n = Math.min(12, events.size());
    for (int i = 0; i < n; i++) {
      JsonNode ev = events.get(i);
      JsonNode comp = ev.path("competitions").isArray() && ev.path("competitions").size() > 0 ? ev.path("competitions").get(0) : null;
      String shortDetail = comp != null ? comp.at("/status/type/shortDetail").asText("") : ev.at("/status/type/shortDetail").asText("");
      String date = ev.path("date").asText("");
      JsonNode competitors = comp != null ? comp.path("competitors") : null;
      String away = teamLine(competitors, "away");
      String home = teamLine(competitors, "home");
      out.append("- ").append(shortDetail.isBlank() ? "NBA" : shortDetail)
          .append(" · ").append(date)
          .append("\n  ").append(away)
          .append("\n  ").append(home)
          .append("\n");
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
}

