/**
 * Model settings client checks (P21.3-D): strict payload validation, CSRF-protected writes, one
 * session renewal after a denial, typed failures, and a request body that carries identifiers only.
 */
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { AT, initialSlots, json, ModelsServer, PROOF, wireSlot } from './models-fixtures';
import { ModelsClient, ModelsError, parseSlot, parseStatus, requestBody, SlotRequest } from './models-client';

const SIGNAL = new AbortController().signal;
const LIGHT: SlotRequest = { slot: 'text.light', backend: 'local', model: 'gemma-4', effort: 'low', declaredClass: null, fallbacks: [], acknowledgeClass: null };

/** Run a promise that must reject with a {@link ModelsError} and return its category. */
async function failureOf(work: Promise<unknown>): Promise<string> {
  try {
    await work;
  } catch (error) {
    expect(error).toBeInstanceOf(ModelsError);
    return (error as ModelsError).kind;
  }
  throw new Error('expected a ModelsError');
}

describe('Model settings client', /** Register owner-route client checks. */ () => {
  let server: ModelsServer;
  let client: ModelsClient;

  beforeEach(/** Each test gets a fresh simulated server and client. */ () => {
    server = new ModelsServer();
    TestBed.configureTestingModule({ providers: [{ provide: LIBRARY_FETCH, useValue: server.fetch }] });
    client = TestBed.inject(ModelsClient);
  });

  it('validates the status payload and fails closed on foreign shapes', /** Nothing partial reaches a screen. */ () => {
    const slots = parseStatus({ slots: initialSlots().reverse() });
    expect(slots.map((slot) => slot.slot)).toEqual(['text.light', 'text.standard', 'text.deliberate', 'image']);
    expect(slots[1].badge).toEqual({ class: 'subscription', billing: 'plan', privacy: 'runtime-vendor', leavesMachine: true });
    expect(slots[1].fallbacks).toEqual([{ backend: 'claude', model: 'model-two', effort: 'low' }]);
    expect(slots[3].badge).toBeNull();

    const light = initialSlots()[0];
    for (const value of [{ slots: initialSlots().slice(1) }, { slots: [...initialSlots().slice(0, 3), initialSlots()[0]] },
      { slots: [...initialSlots(), wireSlot('text.light')] }, []]) {
      expect(() => parseStatus(value), JSON.stringify(value).slice(0, 80)).toThrow(ModelsError);
    }
    const broken: unknown[] = [
      { ...light, slot: 'text.extra' },
      { ...light, source: 'database' },
      { ...light, model: null },
      { ...wireSlot('image'), backend: 'local-image' },
      { ...light, class: { class: 'local', billing: 'per-use', privacy: 'this-machine', leavesMachine: false } },
      { ...light, class: { class: 'gold', billing: 'free', privacy: 'this-machine', leavesMachine: false } },
      { ...light, probe: { state: 'ready', code: null, at: 'yesterday', elapsedMillis: 1 } },
      { ...light, probe: { state: 'failed', code: 'Not A Code', at: AT, elapsedMillis: 1 } },
      { ...light, probe: { state: 'ready', code: null, at: AT, elapsedMillis: -1 } },
      { ...light, probe: { state: 'ready', code: 'adapter-not-installed', at: AT, elapsedMillis: 1 } },
      { ...light, probe: { state: 'failed', code: null, at: AT, elapsedMillis: 1 } },
      { ...light, probe: { state: 'not-run', code: null, at: AT, elapsedMillis: 0 } },
      { ...light, fallbacks: Array.from({ length: 5 }, () => ({ backend: 'local', model: 'm', effort: 'low' })) },
      { ...light, fallbacks: [{ backend: 'local', model: '', effort: 'low' }] },
      { ...light, acknowledged: { class: 'gold', at: AT } },
    ];
    for (const value of broken) expect(() => parseSlot(value), JSON.stringify(value).slice(0, 80)).toThrow(ModelsError);
  });

  it('reads the status with a plain same-origin GET and no write headers', /** Reads carry no CSRF proof. */ async () => {
    const slots = await client.status(SIGNAL);
    expect(slots).toHaveLength(4);
    const read = server.of('GET', '/api/v1/models/status')[0];
    expect(read.headers['X-CSRF-Token']).toBeUndefined();
    expect(read.body).toBeNull();
  });

  it('sends writes with the CSRF proof and a closed JSON body, and returns the stored slot', /** PUT and POST are admitted. */ async () => {
    const saved = await client.save({ ...LIGHT, model: 'gemma-4-small' }, SIGNAL);
    expect(saved.kind).toBe('saved');
    const put = server.of('PUT', '/api/v1/settings/models')[0];
    expect(put.headers['X-CSRF-Token']).toBe(PROOF);
    expect(put.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(put.body!)).toEqual({ slot: 'text.light', backend: 'local', model: 'gemma-4-small', effort: 'low' });
    if (saved.kind === 'saved') expect(saved.slot.model).toBe('gemma-4-small');

    const probed = await client.probe('text.light', SIGNAL);
    expect(probed.kind).toBe('probed');
    const post = server.of('POST', '/api/v1/models/probe')[0];
    expect(post.headers['X-CSRF-Token']).toBe(PROOF);
    expect(JSON.parse(post.body!)).toEqual({ slot: 'text.light' });
    if (probed.kind === 'probed') expect(probed.slot.probe).toEqual({ state: 'unreachable', code: 'adapter-not-installed', at: AT, elapsedMillis: 3 });
  });

  it('serializes only identifiers: the image slot has no effort and unset fields are omitted', /** No key or endpoint field exists. */ () => {
    const image: SlotRequest = { slot: 'image', backend: 'local-image', model: 'sdxl', effort: null, declaredClass: null,
      fallbacks: [{ backend: 'a1111-http', model: 'other', effort: 'none' }], acknowledgeClass: null };
    expect(JSON.parse(requestBody(image))).toEqual({ slot: 'image', backend: 'local-image', model: 'sdxl', fallbacks: [{ backend: 'a1111-http', model: 'other' }] });
    const custom: SlotRequest = { ...LIGHT, backend: 'openai-compatible', declaredClass: 'metered', acknowledgeClass: 'metered',
      fallbacks: [{ backend: 'openai-compatible', model: 'x', effort: 'low' }] };
    expect(Object.keys(JSON.parse(requestBody(custom))).sort()).toEqual(['acknowledgeClass', 'backend', 'declaredClass', 'effort', 'fallbacks', 'model', 'slot']);
  });

  it('reports an unacknowledged class change with both classes and stores nothing', /** The 409 contract. */ async () => {
    const held = await client.save({ ...LIGHT, backend: 'anthropic-api', model: 'm', effort: 'low' }, SIGNAL);
    expect(held).toEqual({ kind: 'acknowledge', from: 'local', to: 'metered' });
    expect(server.slots[0]['backend']).toBe('local');
    const accepted = await client.save({ ...LIGHT, backend: 'anthropic-api', model: 'm', effort: 'low', acknowledgeClass: 'metered' }, SIGNAL);
    expect(accepted.kind).toBe('saved');
  });

  it('returns fixed refusal codes and drops anything that is not a public code', /** Server text never reaches a screen. */ async () => {
    expect(await client.save({ ...LIGHT, model: 'bad model!' }, SIGNAL)).toEqual({ kind: 'refused', code: 'SLOT_MODEL_INVALID' });
    expect(await client.probe('image', SIGNAL)).toEqual({ kind: 'refused', code: 'MODEL_SLOT_NOT_ASSIGNED' });
    const hostile: typeof fetch = (input) => Promise.resolve(String(input) === '/api/v1/session'
      ? json({ csrfToken: PROOF }) : json({ code: 'unexpected text from the server' }, 422));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: LIBRARY_FETCH, useValue: hostile }] });
    expect(await TestBed.inject(ModelsClient).save(LIGHT, SIGNAL)).toEqual({ kind: 'refused', code: '' });
  });

  it('renews the session exactly once after a denial and then reports it', /** No retry loop. */ async () => {
    server.denyNext = 1;
    await client.status(SIGNAL);
    expect(server.of('GET', '/api/v1/session')).toHaveLength(2);
    server.calls.length = 0;
    server.denyNext = 2;
    expect(await failureOf(client.save(LIGHT, SIGNAL))).toBe('denied');
    expect(server.of('PUT', '/api/v1/settings/models')).toHaveLength(2);
  });

  it('maps outages, an unconfigured owner and malformed replies to typed failures', /** Each has its own sentence. */ async () => {
    server.outage = 503;
    expect(await failureOf(client.status(SIGNAL))).toBe('unavailable');
    server.outage = null;
    server.slots = [];
    expect(await failureOf(client.status(SIGNAL))).toBe('invalid');

    const unconfigured: typeof fetch = (input) => Promise.resolve(String(input) === '/api/v1/session'
      ? json({ code: 'LOCAL_OWNER_UNCONFIGURED' }, 503) : json({}, 503));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: LIBRARY_FETCH, useValue: unconfigured }] });
    expect(await failureOf(TestBed.inject(ModelsClient).status(SIGNAL))).toBe('unconfigured');

    const offline: typeof fetch = () => Promise.reject(new TypeError('offline'));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: LIBRARY_FETCH, useValue: offline }] });
    expect(await failureOf(TestBed.inject(ModelsClient).status(SIGNAL))).toBe('unavailable');

    const notJson: typeof fetch = (input) => Promise.resolve(String(input) === '/api/v1/session' ? json({ csrfToken: PROOF }) : new Response('<html>', { status: 200 }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: LIBRARY_FETCH, useValue: notJson }] });
    expect(await failureOf(TestBed.inject(ModelsClient).status(SIGNAL))).toBe('invalid');
  });
});
