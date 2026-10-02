// The Episodes tab's calls to /api/episodes (authoring-api EpisodeController, plan B3c).
import type { EpisodeDetailDto, EpisodeSummary, FeedNotice } from "./episodeTypes";
import { episodePath, failureText, type Failure } from "./episodes";

/** A failed call. [body] is the server's JSON ({error, …}) when it sent one, else its text. */
export class EpisodeApiError extends Error {
  constructor(public status: number, public body: unknown) {
    super(`${status}`);
    this.name = "EpisodeApiError";
  }
}

/** Any failure in the operator's words. A POST with no HTTP answer may still have landed: its outcome is unknown. */
export function describeFailure(e: unknown, sentPost = false): Failure {
  if (e instanceof EpisodeApiError) return failureText(e.status, e.body);
  return sentPost
    ? { text: `응답 없음 — 결과 모름, 상세를 다시 읽은 뒤 판단 · no answer, outcome unknown (${String(e)})`, outcomeUnknown: true }
    : { text: String(e) };
}

async function json<T>(res: Response): Promise<T> {
  const text = await res.text();
  let parsed: unknown = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  if (!res.ok) throw new EpisodeApiError(res.status, parsed);
  return parsed as T;
}

export const listEpisodes = (limit = 50) => fetch(`/api/episodes?limit=${limit}`).then((r) => json<EpisodeSummary[]>(r));

export const getEpisode = (instanceId: string) => fetch(episodePath(instanceId)).then((r) => json<EpisodeDetailDto>(r));

export const noticesAfter = (after: number, limit = 500) =>
  fetch(`/api/episodes/notices?after=${after}&limit=${limit}`).then((r) => json<FeedNotice[]>(r));

/** One decision (design §7.2). The operator goes in the header, never in the body. */
export async function sendDecision(
  instanceId: string,
  action: "decide" | "confirm" | "takeover" | "close",
  operator: string,
  body?: object,
): Promise<string> {
  const headers: Record<string, string> = { "X-Koshei-Operator": operator.trim() };
  if (body) headers["Content-Type"] = "application/json";
  const res = await fetch(episodePath(instanceId, action), { method: "POST", headers, body: body ? JSON.stringify(body) : undefined });
  return (await json<{ reply: string }>(res)).reply;
}
