package com.redhat.rhcl.ai;

public final class TokenEstimator {
  private TokenEstimator() {}

  // Very rough heuristic; good enough for the workshop demo.
  public static int estimateTokens(String text) {
    if (text == null || text.isBlank()) return 0;
    // ~4 chars per token (rough average for English)
    return Math.max(1, (int) Math.ceil(text.length() / 4.0));
  }
}

