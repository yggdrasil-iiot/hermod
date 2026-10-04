import { describe, it, expect } from "vitest";
import { EpisodeApiError, describeFailure } from "./episodeApi";

describe("describeFailure", () => {
  it("says an HTTP failure with the server's body", () => {
    expect(describeFailure(new EpisodeApiError(503, { error: "episode tables not found" })).text).toContain("episode tables not found");
    expect(describeFailure(new EpisodeApiError(504, { error: "EPISODE_TIMEOUT", outcome: "UNKNOWN" }), true).outcomeUnknown).toBe(true);
  });
  it("calls a POST that got no answer an unknown outcome", () => {
    const f = describeFailure(new TypeError("Failed to fetch"), true);
    expect(f.outcomeUnknown).toBe(true);
    expect(f.text).toMatch(/결과 모름/);
  });
  it("says a GET that got no answer as it is", () => {
    const f = describeFailure(new TypeError("Failed to fetch"));
    expect(f.outcomeUnknown).toBeUndefined();
    expect(f.text).toContain("Failed to fetch");
  });
});
