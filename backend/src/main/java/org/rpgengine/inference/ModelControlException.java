package org.rpgengine.inference;

/**
 * A model server or worker did not do what it was asked. It carries a fixed lowercase code and nothing
 * the server said, so neither an endpoint nor server text can reach a metric, a log or a result.
 */
public final class ModelControlException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String code;

    /** @param code fixed lowercase failure code */
    public ModelControlException(String code) {
        super(code);
        this.code = code;
    }

    /** @return the fixed lowercase failure code */
    public String code() {
        return code;
    }
}
