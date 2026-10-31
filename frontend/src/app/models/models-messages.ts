/**
 * Localized wording of the Models panel: slot, class, billing, privacy, probe and refusal text.
 *
 * Every stable code the model settings controllers can answer is a key of {@link ERRORS}; the
 * repository `localization` check fails when a controller code has no entry, so a new backend code
 * cannot reach the owner as a raw identifier. Unknown codes use one generic sentence. Backend and
 * model names are identifiers and are never translated here.
 */
import type { BillingCode, ClassId, ModelsFailure, PrivacyCode, ProbeState, SlotId, SlotSource } from './models-client';

/** Refusal sentences keyed by the exact public code; keys are scanned by the repository check. */
const ERRORS: Readonly<Record<string, () => string>> = {
  LOCAL_ADMISSION_DENIED: () => $localize`:@@models.error.admissionDenied:This browser session is not allowed to change model settings. Reload the page and try again.`,
  MODEL_SETTINGS_STORAGE_UNAVAILABLE: () => $localize`:@@models.error.storageUnavailable:Model settings could not be read or saved. Nothing was changed; try again later.`,
  MODEL_SETTINGS_MALFORMED: () => $localize`:@@models.error.malformed:The assignment was not in a form the engine accepts. Check each field and try again.`,
  MODEL_SETTINGS_TOO_LARGE: () => $localize`:@@models.error.tooLarge:The assignment is too large. Nothing was saved.`,
  MODEL_SLOT_NOT_ASSIGNED: () => $localize`:@@models.error.slotNotAssigned:Assign a model to this slot before checking it.`,
  CLASS_ACKNOWLEDGEMENT_REQUIRED: () => $localize`:@@models.error.acknowledgementRequired:Changing the class of a slot needs your acknowledgement. Nothing was saved.`,
  SLOT_BACKEND_UNSUPPORTED: () => $localize`:@@models.error.backendUnsupported:This backend cannot serve this slot.`,
  SLOT_MODEL_INVALID: () => $localize`:@@models.error.modelInvalid:The model or the effort is not a valid identifier. Nothing was saved.`,
  SLOT_CLASS_DECLARATION_INVALID: () => $localize`:@@models.error.classDeclarationInvalid:A custom endpoint needs a declared class of local or metered, and no other backend takes one. Nothing was saved.`,
  FALLBACK_INVALID: () => $localize`:@@models.error.fallbackInvalid:A fallback is invalid or repeated, or there are more than four. Nothing was saved.`,
  FALLBACK_CLASS_CROSSING: () => $localize`:@@models.error.fallbackClassCrossing:A fallback must stay in the same class as the main assignment. Nothing was saved.`,
};

/**
 * @param code a public error code from the model settings API, or null
 * @returns a specific sentence for known codes, otherwise a safe generic sentence; never the raw code
 */
export function modelsErrorMessage(code: string | null): string {
  const known = code !== null && Object.hasOwn(ERRORS, code) ? ERRORS[code] : undefined;
  return known ? known() : $localize`:@@models.error.generic:The change was not accepted. Nothing was saved; check the fields and try again.`;
}

/** @returns the sentence for a failure to reach or read the model settings */
export function modelsFailureMessage(kind: ModelsFailure): string {
  switch (kind) {
    case 'denied': return $localize`:@@models.failure.denied:This browser session is not allowed to use model settings. Reload the page; only the installation owner can open them.`;
    case 'unconfigured': return $localize`:@@models.failure.unconfigured:This installation has no owner configured, so model settings are unavailable.`;
    case 'invalid': return $localize`:@@models.failure.invalid:The engine sent model settings in a form this page does not understand. Nothing was changed.`;
    default: return $localize`:@@models.failure.unavailable:Model settings could not be reached. Nothing was changed; try again.`;
  }
}

/** @returns the visible name of a slot */
export function slotLabel(slot: SlotId): string {
  switch (slot) {
    case 'text.light': return $localize`:@@models.slot.light:Light text`;
    case 'text.standard': return $localize`:@@models.slot.standard:Standard text`;
    case 'text.deliberate': return $localize`:@@models.slot.deliberate:Deliberate text`;
    default: return $localize`:@@models.slot.image:Images`;
  }
}

/** @returns where a slot's current assignment comes from */
export function sourceLabel(source: SlotSource): string {
  switch (source) {
    case 'settings': return $localize`:@@models.source.settings:Saved in settings`;
    case 'environment': return $localize`:@@models.source.environment:Set when the engine started`;
    default: return $localize`:@@models.source.unassigned:Not assigned`;
  }
}

/** @returns the class name, or the unknown label when the class cannot be established */
export function classLabel(value: ClassId | null): string {
  switch (value) {
    case 'local': return $localize`:@@models.class.local:Local`;
    case 'subscription': return $localize`:@@models.class.subscription:Subscription`;
    case 'metered': return $localize`:@@models.class.metered:Metered`;
    default: return $localize`:@@models.class.unknown:Not known`;
  }
}

/** @returns what the class costs the owner */
export function billingLabel(code: BillingCode): string {
  switch (code) {
    case 'free': return $localize`:@@models.billing.free:Free; uses your own hardware`;
    case 'plan': return $localize`:@@models.billing.plan:Counts against your plan limits`;
    default: return $localize`:@@models.billing.perUse:Billed per use by the vendor`;
  }
}

/** @returns who receives the prompts under the class */
export function privacyLabel(code: PrivacyCode): string {
  switch (code) {
    case 'this-machine': return $localize`:@@models.privacy.thisMachine:Prompts stay on this machine`;
    case 'runtime-vendor': return $localize`:@@models.privacy.runtimeVendor:Prompts leave this machine for the runtime vendor`;
    default: return $localize`:@@models.privacy.apiVendor:Prompts leave this machine for the API vendor`;
  }
}

/** @returns the outcome of the last readiness probe */
export function probeStateLabel(state: ProbeState): string {
  switch (state) {
    case 'ready': return $localize`:@@models.probe.ready:Ready`;
    case 'failed': return $localize`:@@models.probe.failed:Not ready`;
    case 'unreachable': return $localize`:@@models.probe.unreachable:Unreachable`;
    default: return $localize`:@@models.probe.notRun:Not checked yet`;
  }
}

/** Probe reason sentences keyed by the fixed code the backend stores. */
const PROBE_CODES: Readonly<Record<string, () => string>> = {
  'adapter-not-installed': () => $localize`:@@models.probe.code.adapterNotInstalled:No adapter for this backend is installed in this engine.`,
  'model-not-approved': () => $localize`:@@models.probe.code.modelNotApproved:This model is not on your approved list.`,
  'runtime-starting': () => $localize`:@@models.probe.code.runtimeStarting:The runtime is still starting.`,
  'runtime-unavailable': () => $localize`:@@models.probe.code.runtimeUnavailable:The runtime is unavailable.`,
  'runtime-stopped': () => $localize`:@@models.probe.code.runtimeStopped:The runtime is stopped.`,
  'probe-error': () => $localize`:@@models.probe.code.probeError:The check could not finish.`,
};

/** @returns the sentence for a probe reason code; unknown codes get a generic sentence, never the raw code */
export function probeCodeMessage(code: string): string {
  const known = Object.hasOwn(PROBE_CODES, code) ? PROBE_CODES[code] : undefined;
  return known ? known() : $localize`:@@models.probe.code.unknown:The check failed for a reason this page does not know.`;
}
