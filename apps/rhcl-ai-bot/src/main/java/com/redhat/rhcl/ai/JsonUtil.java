package com.redhat.rhcl.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class JsonUtil {
  @Inject
  ObjectMapper mapper;

  public ObjectMapper mapper() {
    return mapper;
  }

  public JsonNode readTree(String json) throws JsonProcessingException {
    return mapper.readTree(json);
  }

  public String write(JsonNode node) throws JsonProcessingException {
    return mapper.writeValueAsString(node);
  }

  public ObjectNode obj() {
    return mapper.createObjectNode();
  }
}

