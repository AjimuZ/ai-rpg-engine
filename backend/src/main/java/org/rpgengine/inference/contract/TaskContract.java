package org.rpgengine.inference.contract;

import java.util.List;

/**
 * One versioned application task contract: instruction template, output schema and independent
 * validator for a context type {@code C} and typed proposal {@code T}.
 *
 * <p>Implementations are stateless and thread-safe. Rendering is deterministic for equal inputs,
 * so the same canonical context always yields the same digest and the single repair reuses it.
 *
 * @param <C> trusted context type
 * @param <T> typed proposal type
 */
public interface TaskContract<C extends ContractContext, T> {
    /** Version of every contract envelope in this package. */
    int CONTRACT_VERSION = 1;

    /** @return task kind, role and default budget */
    TaskKind kind();

    /** @return JSON Schema text supplied as the task output schema; constrains generation only */
    String outputSchema();

    /** @return SHA-256 of the instruction template and schema, recorded as prompt provenance */
    String templateHash();

    /**
     * Render task instructions.
     *
     * @param context trusted context
     * @param repair violations of the previous attempt; empty for the first attempt. Only fixed
     *        codes and pointers are rendered, never earlier output text
     * @return instruction text and the digest of the rendered context
     * @throws ContextScopeException when the rendered context contains a guard marker
     * @throws IllegalArgumentException when the instructions exceed the task port bound
     */
    RenderedTask render(C context, List<ContractViolation> repair);

    /**
     * Validate untrusted output structurally and semantically.
     *
     * @param context the context the output was requested for
     * @param output final agent message of a completed task
     * @return accepted typed proposal or every violation found; never throws for bad output
     */
    Validation<T> validate(C context, String output);

    /**
     * Rendered instructions.
     *
     * @param instructions full instruction text for the task port
     * @param contextDigest lowercase SHA-256 of the canonical context JSON, echoed by the output
     */
    record RenderedTask(String instructions, String contextDigest) {
    }
}
