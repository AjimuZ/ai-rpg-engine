/**
 * Simulated owner model settings server for the Models specs (P21.3-D).
 *
 * It answers the three owner routes with the shapes `ModelSettingsController` produces and applies
 * the same acknowledgement rule: an assignment whose class differs from the slot's current class
 * (an unassigned slot counts as `local`) is refused with 409 until `acknowledgeClass` names the new
 * class. It is a test double, not the backend; the backend's own behavior is covered by
 * `ModelSettingsHttpTest`.
 */

/** CSRF proof the simulated session hands out; long enough to satisfy the client's format check. */
export const PROOF = 'csrf-proof-0123456789abcdefghij';
export const AT = '2026-10-31T10:15:00Z';

const CLASSES: Readonly<Record<string, { class: string; billing: string; privacy: string; leavesMachine: boolean }>> = {
  local: { class: 'local', billing: 'free', privacy: 'this-machine', leavesMachine: false },
  subscription: { class: 'subscription', billing: 'plan', privacy: 'runtime-vendor', leavesMachine: true },
  metered: { class: 'metered', billing: 'per-use', privacy: 'api-vendor', leavesMachine: true },
};
const FIXED_CLASS: Readonly<Record<string, string>> = {
  codex: 'subscription', claude: 'subscription', local: 'local', 'local-image': 'local', 'a1111-http': 'local',
  'anthropic-api': 'metered', 'openai-images': 'metered',
};

/** One wire-format slot; `over` replaces fields of an unassigned slot. */
export function wireSlot(slot: string, over: Record<string, unknown> = {}): Record<string, unknown> {
  return { slot, source: 'unassigned', backend: null, model: null, effort: null, class: null, fallbacks: [], acknowledged: null,
    probe: { state: 'not-run', code: null, at: null, elapsedMillis: 0 }, updatedAt: null, ...over };
}

/** Four slots: a local one, a probed subscription one with a fallback, an unassigned text slot and an unassigned image slot. */
export function initialSlots(): Record<string, unknown>[] {
  return [
    wireSlot('text.light', { source: 'settings', backend: 'local', model: 'gemma-4', effort: 'low', class: CLASSES['local'],
      probe: { state: 'ready', code: null, at: AT, elapsedMillis: 42 }, updatedAt: AT }),
    wireSlot('text.standard', { source: 'settings', backend: 'codex', model: 'model-one', effort: 'medium', class: CLASSES['subscription'],
      fallbacks: [{ backend: 'claude', model: 'model-two', effort: 'low' }], acknowledged: { class: 'subscription', at: AT },
      probe: { state: 'failed', code: 'model-not-approved', at: AT, elapsedMillis: 7 }, updatedAt: AT }),
    wireSlot('text.deliberate'),
    wireSlot('image'),
  ];
}

/** A request the server received. */
export interface Call {
  readonly method: string;
  readonly path: string;
  readonly headers: Record<string, string>;
  readonly body: string | null;
}

/** Build a JSON response with the server's content type. */
export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Simulated local server; every request is recorded in {@link calls}. */
export class ModelsServer {
  readonly calls: Call[] = [];
  slots = initialSlots();
  /** Number of upcoming non-session requests answered 403 before the server recovers. */
  denyNext = 0;
  /** When set, every non-session request is answered with this status and a storage-unavailable body. */
  outage: number | null = null;
  /** When set, a save is answered only after this promise settles, so a test can observe the in-flight state. */
  gate: Promise<void> | null = null;

  /** Serve one request like the owner routes do. */
  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = String(input);
    const method = init?.method ?? 'GET';
    this.calls.push({ method, path, headers: { ...(init?.headers as Record<string, string> | undefined) }, body: typeof init?.body === 'string' ? init.body : null });
    if (path === '/api/v1/session') return Promise.resolve(json({ csrfToken: PROOF }));
    if (this.denyNext > 0) { this.denyNext--; return Promise.resolve(json({ code: 'LOCAL_ADMISSION_DENIED' }, 403)); }
    if (this.outage !== null) return Promise.resolve(json({ code: 'MODEL_SETTINGS_STORAGE_UNAVAILABLE' }, this.outage));
    const csrf = (init?.headers as Record<string, string> | undefined)?.['X-CSRF-Token'];
    if (method !== 'GET' && csrf !== PROOF) return Promise.resolve(json({ code: 'LOCAL_ADMISSION_DENIED' }, 403));
    if (method === 'GET' && path === '/api/v1/models/status') return Promise.resolve(json({ slots: this.slots }));
    if (method === 'PUT' && path === '/api/v1/settings/models') {
      const request = JSON.parse(String(init?.body));
      return (this.gate ?? Promise.resolve()).then(() => this.save(request));
    }
    if (method === 'POST' && path === '/api/v1/models/probe') return Promise.resolve(this.probe(JSON.parse(String(init?.body)).slot));
    return Promise.resolve(json({ code: 'NOT_FOUND' }, 404));
  };

  /** @returns the requests of one method and path, in order */
  of(method: string, path: string): Call[] {
    return this.calls.filter((call) => call.method === method && call.path === path);
  }

  private find(slot: string): Record<string, unknown> {
    return this.slots.find((candidate) => candidate['slot'] === slot)!;
  }

  private save(request: Record<string, unknown>): Response {
    const slot = this.find(request['slot'] as string);
    const backend = request['backend'] as string;
    if (!/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/.test(request['model'] as string)) return json({ code: 'SLOT_MODEL_INVALID' }, 422);
    const declared = request['declaredClass'] as string | undefined;
    const next = backend === 'openai-compatible' ? declared : FIXED_CLASS[backend];
    if (next === undefined) return json({ code: 'SLOT_CLASS_DECLARATION_INVALID' }, 422);
    const current = (slot['class'] as { class: string } | null)?.class ?? 'local';
    if (current !== next && request['acknowledgeClass'] !== next) return json({ code: 'CLASS_ACKNOWLEDGEMENT_REQUIRED', from: current, to: next }, 409);
    const listed = request['fallbacks'] as Record<string, string>[] | undefined ?? [];
    // Like the backend, a fallback outside the primary's class is refused and nothing is stored.
    if (listed.some((entry) => (entry['backend'] === 'openai-compatible' ? declared : FIXED_CLASS[entry['backend']]) !== next)) {
      return json({ code: 'FALLBACK_CLASS_CROSSING' }, 422);
    }
    const fallbacks = listed.map(
      (entry) => ({ backend: entry['backend'], model: entry['model'], effort: entry['effort'] ?? 'none' }));
    const acknowledged = current !== next ? { class: next, at: AT } : slot['acknowledged'];
    Object.assign(slot, { source: 'settings', backend, model: request['model'], effort: request['effort'] ?? 'none', class: CLASSES[next],
      fallbacks, acknowledged, probe: { state: 'not-run', code: null, at: null, elapsedMillis: 0 }, updatedAt: AT });
    return json({ slot });
  }

  private probe(id: string): Response {
    const slot = this.find(id);
    if (slot['backend'] === null) return json({ code: 'MODEL_SLOT_NOT_ASSIGNED' }, 404);
    slot['probe'] = { state: 'unreachable', code: 'adapter-not-installed', at: AT, elapsedMillis: 3 };
    return json({ slot });
  }
}
