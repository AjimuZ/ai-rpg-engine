package org.rpgengine.inference.codex;

import tools.jackson.databind.JsonNode;

/**
 * One runtime notification.
 *
 * @param method notification method, non-empty
 * @param params notification parameters, always an object
 */
record RpcEvent(String method, JsonNode params) {
}
