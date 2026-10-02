// OperatorCardView = the operator card (design §9.5 order, until §19 D settles it). It draws only the fields the
// server put on the card and leaves empty ones out. There is no `cause` here: the server never puts one on a card.
import { Fragment } from "react";
import type { OperatorCard, Unknown } from "./episodeTypes";
import { foundInText } from "./episodes";

const pairs = (o: Record<string, unknown>) =>
  Object.entries(o).map(([k, v]) => `${k}=${v == null ? "null" : typeof v === "object" ? JSON.stringify(v) : String(v)}`).join(" ");

const unknownLine = (u: Unknown) => `${u.what} · ${pairs(u.subject)}`;

const text = (v: unknown) => (v == null ? "" : String(v));

export function OperatorCardView({ card }: { card: OperatorCard }) {
  const joinedRows = card.joinedAfterSnapshot;
  const joinedCount = card.joinedAfterSnapshotCount ?? joinedRows.length;
  const hasUnverified = card.unverifiedCitations.length > 0 || card.unlocatedCitations > 0
    || card.unverifiedNumbers.length > 0 || card.unlocatedNumbers > 0;
  const hasSource = card.diagnosisFromRecord || card.diagnosisAttempt != null;

  return (
    <div className="panel episodes-card" data-testid="operator-card">
      <div className="panel-body">
        {/* 1. What could not be confirmed comes first. */}
        {card.unknowns.length > 0 && (
          <div className="banner await" data-testid="card-unknowns">
            <div>
              <div>확인 불가 · unknowns ({card.unknowns.length})</div>
              <ul>
                {card.unknowns.map((u, i) => <li key={i} className="mono">{unknownLine(u)}</li>)}
              </ul>
            </div>
          </div>
        )}

        {/* 2. Facts still arriving. */}
        {card.factsBehind && (
          <div className="banner info" data-testid="card-facts-behind">기록이 아직 다 오지 않음 · records still arriving</div>
        )}

        {/* 3. Facts. */}
        {card.symptoms.length > 0 && (
          <section data-testid="card-symptoms">
            <div className="muted">사실 · facts ({card.symptoms.length})</div>
            {card.symptoms.map((s, i) => <div key={i} className="mono">{JSON.stringify(s)}</div>)}
          </section>
        )}

        {/* 4. The proposal: kind and ref as they came; the id is not taken apart. */}
        {card.proposal && (
          <section data-testid="card-proposal">
            <div className="muted">제안 · proposal</div>
            <dl>
              <dt>kind</dt>
              <dd className="mono">{card.proposal.kind}</dd>
              {Object.entries(card.proposal.ref).map(([k, v]) => (
                <Fragment key={k}>
                  <dt>{k}</dt>
                  <dd className="mono">{v ?? "null"}</dd>
                </Fragment>
              ))}
            </dl>
          </section>
        )}

        {/* 5. Where the diagnosis came from — only when it applies. */}
        {hasSource && (
          <div className="muted" data-testid="card-diagnosis-source">
            {card.diagnosisFromRecord && <span>기록에서 읽은 진단 · diagnosis read from the record</span>}
            {card.diagnosisFromRecord && card.diagnosisAttempt != null && <span> · </span>}
            {card.diagnosisAttempt != null && <span>시도 {card.diagnosisAttempt} · attempt {card.diagnosisAttempt}</span>}
          </div>
        )}
        {card.diagnosisRefused && (
          <div className="banner err" data-testid="card-refused">
            <span>
              판정이 거절한 답 · refused by the judge: {card.diagnosisRefused}
              {card.diagnosisRefusedDetail ? ` — ${card.diagnosisRefusedDetail}` : ""}
            </span>
          </div>
        )}
        {card.responseUnreadable && (
          <div className="banner err" data-testid="card-unreadable">읽을 수 없는 답 · unreadable answer</div>
        )}

        {/* 6. Rationale, or the plain statement that there is none. */}
        {(card.rationale || card.rationaleMissing) && (
          <section data-testid="card-rationale">
            <div className="muted">이유 · rationale</div>
            <div>{card.rationale ? card.rationale : "이유 없음 · no rationale"}</div>
          </section>
        )}

        {/* 7. Guidance. */}
        {card.guidance.length > 0 && (
          <section data-testid="card-guidance">
            <div className="muted">지침 · guidance</div>
            {card.guidance.map((g, i) => <div key={i}>{g.label}: {g.text}</div>)}
          </section>
        )}

        {/* 8. Citations: documents that were in the bundle, not facts (R13). */}
        {card.citations.length > 0 && (
          <section data-testid="card-citations">
            <div className="muted">근거 꾸러미에 있던 문서 · documents in the evidence bundle (not facts)</div>
            {card.citations.map((c, i) => (
              <div key={i} className="mono">{[text(c.title), text(c.section)].filter(Boolean).join(" · ")}</div>
            ))}
          </section>
        )}

        {/* 9. Sentences with no citation. */}
        {card.uncitedSentences.length > 0 && (
          <section data-testid="card-uncited">
            <div className="muted">인용 없는 문장 · uncited sentences ({card.uncitedSentences.length})</div>
            {card.uncitedSentences.map((s, i) => <div key={i}>{s}</div>)}
          </section>
        )}

        {/* 10. Claims that could not be checked. */}
        {hasUnverified && (
          <section data-testid="card-unverified">
            <div className="muted">확인 못 한 주장 · unverified claims</div>
            {card.unverifiedCitations.map((c, i) => <div key={"c" + i}>{c.text}</div>)}
            {card.unlocatedCitations > 0 && (
              <div>본문 없는 인용 {card.unlocatedCitations}개 · {card.unlocatedCitations} citations without text</div>
            )}
            {card.unverifiedNumbers.map((c, i) => {
              const where = foundInText(c.foundIn);
              return <div key={"n" + i}><span className="mono">{c.text}</span>{where ? ` — ${where}` : ""}</div>;
            })}
            {card.unlocatedNumbers > 0 && (
              <div>위치 모르는 숫자 {card.unlocatedNumbers}개 · {card.unlocatedNumbers} numbers, location unknown</div>
            )}
          </section>
        )}

        {/* 11. The precondition a person is asked to confirm, and what joined after the snapshot. */}
        {(card.precondition || joinedCount > 0) && (
          <section data-testid="card-precondition">
            <div className="muted">확인할 전제 · precondition to confirm</div>
            {card.precondition && <div className="episodes-precondition">{card.precondition}</div>}
            {joinedCount > 0 && (
              <div>
                <div>
                  스냅샷 뒤 합류한 증상 · symptoms joined after the snapshot ({joinedCount})
                  {joinedRows.length < joinedCount ? " · 기록이 늦음 · records lagging" : ""}
                </div>
                {joinedRows.map((s, i) => <div key={i} className="mono">{JSON.stringify(s)}</div>)}
              </div>
            )}
          </section>
        )}

        {/* 12. Who is deciding, and how sure the screen is of it. */}
        {card.identityAssurance && (
          <div className="muted" data-testid="card-identity">
            {card.identityAssurance === "SELF_ASSERTED"
              ? "스스로 밝힌 신원 · self-asserted identity (no authentication)"
              : card.identityAssurance}
          </div>
        )}
      </div>
    </div>
  );
}
