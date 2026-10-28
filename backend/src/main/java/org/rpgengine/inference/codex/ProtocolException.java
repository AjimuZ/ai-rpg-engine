package org.rpgengine.inference.codex;

/**
 * Protocol, correlation or policy failure with a fixed diagnostic code only. Raw runtime
 * messages are never attached. Whether the failure also closed the RPC generation is visible
 * through {@link RpcGeneration#failure()}.
 */
final class ProtocolException extends Exception {
    private static final long serialVersionUID = 1L;

    /** @param code fixed lowercase code */
    ProtocolException(String code) {
        super(code, null, false, false);
    }

    /** @return the fixed code */
    String code() {
        return getMessage();
    }
}
