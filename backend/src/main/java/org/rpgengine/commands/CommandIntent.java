package org.rpgengine.commands;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable typed evidence that binds trusted resolution to the admitted owner, request
 * and exact command hash. It contains intentions only; constructing it grants no
 * authorization. Canonical commit checks it against the journal and current state under
 * the campaign lock. It is internal resolver input, never an HTTP resolved-effect DTO.
 */
public record CommandIntent(UUID ownerId, String requestId, long expectedRevision, TypedCommand command) {
    /** Reject missing identities and invalid envelope counters before taking database locks. */
    public CommandIntent {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(command, "command");
        // Reuse envelope bounds; the sealed command supplies an immutable closed payload.
        new OperationAdmissionService.Command(requestId, expectedRevision, command.type(), command.payload());
    }

    /** Freeze one envelope from the same canonical bytes used for admission hashing. */
    public static CommandIntent from(UUID owner, OperationAdmissionService.Command envelope) {
        byte[] canonical = CommandCanonicalForm.encode(envelope.commandType(), envelope.expectedRevision(),
                envelope.payload(), OperationAdmissionService.MAX_CANONICAL_BYTES);
        return new CommandIntent(owner, envelope.requestId(), envelope.expectedRevision(), TypedCommand.decode(canonical));
    }

    /** @return the exact version-1 identity to compare with the journal before any new effects */
    String payloadHash() {
        return CommandCanonicalForm.sha256(CommandCanonicalForm.encode(command.type(), expectedRevision,
                command.payload(), OperationAdmissionService.MAX_CANONICAL_BYTES));
    }
}
