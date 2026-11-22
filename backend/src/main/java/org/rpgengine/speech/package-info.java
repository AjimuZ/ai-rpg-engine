/**
 * Optional local speech (P17.7): the recorded qualification of text-to-speech candidates, the rule that enables
 * speech only when every gate has passed, and the text-only fallback every line takes otherwise. No worker,
 * model, voice or audio exists here; synthesis, voice identities and playback belong to a later story that
 * can pass the gates.
 *
 * <p>Speech recognition (P17.8-A) is recorded separately in {@link org.rpgengine.speech.SpeechInputQualification}:
 * no recognizer is qualified, so capture is never allowed and typed play is the only input path.
 * {@link org.rpgengine.speech.SpeechInput} (P17.9-A) is the matching no-capture path: a request to listen reports
 * {@code unavailable} with reasons and opens nothing, and a recognition result yields no draft and queues nothing.
 */
package org.rpgengine.speech;
