/**
 * Replaying single origin for specs that consume the recorded real-server transcript (P05.6, QA-35).
 *
 * `campaign-reload-transcript.json` is written by the backend test `CampaignInterfaceContractTest`
 * against the actual servlet stack and PostgreSQL and is kept byte-identical to the backend golden
 * file by the repository `contracts` check. This module only reads it: it exposes the recorded
 * identities and a fetch replacement that answers exactly what the real server answered, so specs
 * cannot drift from the producer's bytes. It deliberately imports no application module, which lets
 * the Italian-variant spec load translations before any `$localize` call is evaluated.
 */
import transcript from './campaign-reload-transcript.json';

/** One recorded HTTP exchange; bodies are the server's exact (normalized) text. */
export interface Exchange {
  readonly phase: string;
  readonly client: string;
  readonly method: string;
  readonly path: string;
  readonly requestBody: string | null;
  readonly status: number;
  readonly contentType: string | null;
  readonly body: string;
}

export const TRANSCRIPT = transcript;
export const EXCHANGES: readonly Exchange[] = transcript.exchanges;
export const PHASES: readonly string[] = transcript.phases;
export const { adventureId: ADVENTURE, campaignId: CAMPAIGN, moveOperationId: MOVE, waitOperationId: WAIT,
  foreignCampaignId: FOREIGN_CAMPAIGN } = transcript.identities;
export const SCOPE = { adventureId: ADVENTURE, campaignId: CAMPAIGN };
/** Routed playthrough URL inside one locale variant (the locale prefix is the base href). */
export const ROUTE = `/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`;
export const SYNC_KEY = `engine.campaign-sync.v1:${JSON.stringify([ADVENTURE, CAMPAIGN])}`;
export const DRAFT_KEY = `engine.story-draft.v1:${JSON.stringify([ADVENTURE, CAMPAIGN])}`;

/**
 * Replay the transcript as the single origin. A request is answered with the latest page exchange
 * recorded for the same method and path at or before the current phase. A stream reply without
 * messages stays open, like the real held long poll, until the phase advances or the request is
 * aborted; everything else ends immediately.
 *
 * Unrecorded requests reject like a network failure and are listed in {@link unrecorded}, so a
 * renamed, extra or submitting request is visible to the spec. Fixture-side writes (the commands
 * and cancellation another client sent) are never served to the interface.
 */
export class TranscriptServer {
  readonly calls: string[] = [];
  readonly unrecorded: string[] = [];
  private phase = 0;
  private readonly held = new Set<() => void>();

  /**
   * Move to a later recorded phase; held polls end so the page reconnects and sees the new answers.
   *
   * @throws Error when the phase is unknown or not strictly after the current one
   */
  advance(phase: string): void {
    const next = PHASES.indexOf(phase);
    if (next <= this.phase) throw new Error(`Phase ${phase} is unknown or not after ${PHASES[this.phase]}`);
    this.phase = next;
    for (const end of [...this.held]) end();
  }

  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = String(input);
    const method = init?.method ?? 'GET';
    this.calls.push(`${method} ${path}`);
    const answer = EXCHANGES.filter(/** Fixture writes are never served to the interface. */ (entry) => entry.client === 'page' &&
      entry.method === method && entry.path === path && PHASES.indexOf(entry.phase) <= this.phase).at(-1);
    if (!answer && method === 'GET' && path === `/api/v1/campaigns/${CAMPAIGN}/artwork?adventureId=${ADVENTURE}`) {
      // The artwork listing (P17.1-B) postdates the recorded transcript and its producer test does not record it yet, so this
      // one read is answered with the empty listing the real route returns for a campaign without artwork. Nothing else is invented.
      return Promise.resolve(new Response(JSON.stringify({ scope: SCOPE, artwork: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }
    if (!answer && method === 'GET' && path === `/api/v1/campaigns/${CAMPAIGN}/music?adventureId=${ADVENTURE}`) {
      // The music listing (P17.2-D) also postdates the transcript; it is answered with what the real route returns for a pack
      // that declares no music: no policy and no track.
      return Promise.resolve(new Response(JSON.stringify({ scope: SCOPE, policy: null, tracks: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }
    if (!answer && method === 'GET' && path === `/api/v1/campaigns/${CAMPAIGN}/artwork/requests?adventureId=${ADVENTURE}`) {
      // The runtime artwork listing (P17.6-C) postdates the transcript as well. It is answered with the packaged default, where
      // generation is off (`disabled`), an unused default budget and no subject; the subject list itself is not recorded.
      return Promise.resolve(new Response(JSON.stringify({ scope: SCOPE, capability: 'disabled',
        budget: { charged: 0, limit: 12, queued: 0, running: false }, subjects: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }
    if (!answer) {
      this.unrecorded.push(`${method} ${path}`);
      return Promise.reject(new TypeError('Request was not recorded from the real server'));
    }
    const headers = answer.contentType === null ? undefined : { 'Content-Type': answer.contentType };
    if (!path.includes('/stream?')) return Promise.resolve(new Response(answer.body, { status: answer.status, headers }));
    const bytes = new TextEncoder().encode(answer.body);
    const holds = !/(^|\n)id: /.test(answer.body);
    const body = new ReadableStream<Uint8Array>({
      start: (controller) => {
        controller.enqueue(bytes);
        if (!holds) { controller.close(); return; }
        const end = (): void => { this.held.delete(end); try { controller.close(); } catch { /* Already aborted. */ } };
        this.held.add(end);
        init?.signal?.addEventListener('abort', /** Leaving the page aborts the held poll. */ () => {
          this.held.delete(end);
          try { controller.error(new DOMException('Aborted', 'AbortError')); } catch { /* Already closed. */ }
        }, { once: true });
      },
    });
    return Promise.resolve(new Response(body, { status: answer.status, headers }));
  };
}
