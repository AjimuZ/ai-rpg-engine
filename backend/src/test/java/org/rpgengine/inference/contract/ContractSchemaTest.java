package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Drift between the bundled output schemas (which only constrain generation) and the independent
 * Java validators: every object in a schema must list exactly the validator's field set, require
 * all of it (strict structured-output style) and forbid additional properties.
 */
class ContractSchemaTest {
    static JsonNode schema(TaskContract<?, ?> contract) {
        return ContractFixtures.JSON.readTree(contract.outputSchema());
    }

    static void assertClosedObject(JsonNode node, List<String> fields) {
        assertThat(node.path("additionalProperties").asBoolean(true)).isFalse();
        var properties = new ArrayList<String>(node.path("properties").propertyNames());
        var required = new ArrayList<String>();
        node.path("required").forEach(value -> required.add(value.asString()));
        assertThat(properties).containsExactlyElementsOf(fields);
        assertThat(required).containsExactlyElementsOf(fields);
    }

    @Test
    void intentSchemaMatchesTheValidatorFieldSets() {
        JsonNode root = schema(new IntentContract());
        assertClosedObject(root, ContractEnvelope.RESPONSE_FIELDS);
        JsonNode body = root.path("properties").path("body");
        assertClosedObject(body, IntentContract.BODY_FIELDS);
        JsonNode clarification = body.path("properties").path("clarification");
        assertClosedObject(clarification, IntentContract.CLARIFICATION_FIELDS);
        assertClosedObject(clarification.path("properties").path("interpretations").path("items"),
                IntentContract.INTERPRETATION_FIELDS);
        var statuses = new ArrayList<String>();
        root.path("properties").path("status").path("enum").forEach(v -> statuses.add(v.asString()));
        assertThat(statuses).containsExactlyInAnyOrderElementsOf(IntentContract.STATUSES);
    }

    @Test
    void narrationSchemaMatchesTheValidatorFieldSets() {
        JsonNode root = schema(new NarrationContract());
        assertClosedObject(root, ContractEnvelope.RESPONSE_FIELDS);
        JsonNode body = root.path("properties").path("body");
        assertClosedObject(body, NarrationContract.BODY_FIELDS);
        assertClosedObject(body.path("properties").path("passages").path("items"), NarrationContract.PASSAGE_FIELDS);
        assertClosedObject(body.path("properties").path("suggestions").path("items"), NarrationContract.SUGGESTION_FIELDS);
        assertThat(body.path("properties").path("suggestions").path("maxItems").asInt()).isEqualTo(3);
        assertThat(body.path("properties").path("passages").path("items").path("properties").path("text")
                .path("maxLength").asInt()).isEqualTo(NarrationContract.MAX_PASSAGE);
    }

    @Test
    void templatesAreVersionedEnglishAndHashed() {
        var intent = new IntentContract();
        var narration = new NarrationContract();
        assertThat(intent.templateHash()).matches("[0-9a-f]{64}").isEqualTo(new IntentContract().templateHash())
                .isNotEqualTo(narration.templateHash());
        assertThat(TaskContract.CONTRACT_VERSION).isEqualTo(1);
        // Technical templates stay English whatever the output locale; only output strings switch.
        for (String text : List.of(ContractEnvelope.SHARED_INSTRUCTION, IntentContract.TASK_INSTRUCTION,
                NarrationContract.TASK_INSTRUCTION)) {
            assertThat(Pattern.compile("[^\\x20-\\x7E\\n]").matcher(text).find()).isFalse();
        }
    }
}
