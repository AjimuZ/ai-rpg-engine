package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rpgengine.inference.contract.ContractFixtures.JSON;
import static org.rpgengine.inference.contract.ContractFixtures.envelope;
import static org.rpgengine.inference.contract.ContractFixtures.list;
import static org.rpgengine.inference.contract.ContractFixtures.narration;
import static org.rpgengine.inference.contract.ContractFixtures.narrationBody;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.local.LocalAdapters;
import org.rpgengine.inference.local.ScriptedModelServer;
import org.rpgengine.inference.scheduling.SchedulerFixtures;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.SupportedLocale;

/**
 * The local adapter's output passes through the same runner as every adapter's: this suite wires the
 * real runner, scheduler and session registry over the adapter and a scripted HTTP server, which is a
 * fixture and not a model. Valid output is accepted; schema, disclosure and text violations are rejected
 * after the single repair; a transport failure is not repaired.
 */
class LocalAdapterContractTest {
    private final NarrationContract contract = new NarrationContract();
    private final NarrationContext context = narration(SupportedLocale.EN);
    private ScriptedModelServer server;
    private LocalAdapters.Running adapter;
    private ContractRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedModelServer("model-a");
        var assignment = SlotAssignment.of("local", new EffectiveSettings("model-a", "none"));
        adapter = LocalAdapters.start(server, new ProfileResolver(slot -> Optional.of(assignment),
                ModelAllowlist.empty(), Optional.empty()));
        var sessions = new ScopedSessions(adapter, ScopedSessions.DEFAULT_CAPACITY);
        runner = new ContractRunner(SchedulerFixtures.scheduler(sessions, adapter, Clock.systemUTC()), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        adapter.close();
        server.close();
    }

    private String valid() {
        return output(contract, context, "proposed", narrationBody(SupportedLocale.EN));
    }

    /** A valid output whose first passage says {@code text}. */
    private String saying(String text) {
        var body = narrationBody(SupportedLocale.EN);
        list(body, "passages").get(0).put("text", text);
        return output(contract, context, "proposed", body);
    }

    private ContractOutcome<NarrationProposal> run() throws Exception {
        return runner.start(contract, context, InferenceSlot.STANDARD, Duration.ofSeconds(30)).outcome()
                .toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    /** Script {@code invalid} for both attempts and require the rejection, with the single repair. */
    private void assertRejected(String invalid, String code, String neverEchoed) throws Exception {
        server.answers(invalid).answers(invalid);
        var outcome = (ContractOutcome.Rejected<NarrationProposal>) run();
        assertThat(outcome.violations()).extracting(ContractViolation::code).contains(code);
        assertThat(outcome.attempts()).hasSize(2);
        assertThat(outcome.attempts()).allSatisfy(attempt -> {
            assertThat(attempt.status()).isEqualTo(TaskResult.Status.COMPLETED);
            assertThat(attempt.provenance().runtime()).isEqualTo("local");
            assertThat(attempt.violationCodes()).contains(code);
        });
        assertThat(server.chats()).hasSize(2);
        // The repair instructions name the violation and never repeat the rejected text.
        assertThat(server.lastMessages().getLast()).contains("REPAIR", code).doesNotContain(neverEchoed);
    }

    @Test
    void validOutputIsAcceptedAndTheContractSchemaIsSent() throws Exception {
        server.answers(valid());
        var outcome = (ContractOutcome.Accepted<NarrationProposal>) run();
        assertThat(outcome.repaired()).isFalse();
        assertThat(outcome.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.provenance()).isEqualTo(new TaskResult.Provenance("local", "llama.cpp-b11960", "model-a",
                    "none"));
            assertThat(attempt.freshSession()).isTrue();
        });
        var sent = server.chats().getFirst().path("response_format").path("json_schema").path("schema");
        assertThat(sent).isEqualTo(JSON.readTree(contract.outputSchema()));
    }

    @Test
    void outputThatIsNotJsonIsRejected() throws Exception {
        assertRejected("Zanzibarico is not a JSON document", "malformed-json", "Zanzibarico");
    }

    @Test
    void outputBreakingTheSchemaIsRejected() throws Exception {
        var root = envelope(contract, context, "proposed", narrationBody(SupportedLocale.EN));
        root.put("zanzibarico", 1);
        assertRejected(JSON.writeValueAsString(root), "field-set", "\"zanzibarico\":1");
    }

    @Test
    void outputDisclosingASecretIsRejected() throws Exception {
        assertRejected(saying("Mara leans closer and whispers about the " + ContractFixtures.SECRET + " tonight."),
                "scope-leak", ContractFixtures.SECRET + " tonight");
    }

    @Test
    void outputFailingATextCheckIsRejected() throws Exception {
        assertRejected(saying("Zanzibarico hits you and you lose 12 points."), "unsupported-number", "Zanzibarico");
    }

    @Test
    void aSuccessfulRepairIsAcceptedInTheSameConversation() throws Exception {
        String bad = saying("Zanzibarico hits you and you lose 12 points.");
        server.answers(bad).answers(valid());
        var outcome = (ContractOutcome.Accepted<NarrationProposal>) run();
        assertThat(outcome.repaired()).isTrue();
        assertThat(outcome.attempts()).extracting(ContractOutcome.Attempt::freshSession).containsExactly(true, false);
        // The history holds the earlier attempt, as a runtime conversation would.
        assertThat(server.lastMessages()).hasSize(3);
    }

    @Test
    void aServerFailureEndsTheCallWithoutARepair() throws Exception {
        server.replies(500, "{\"error\":\"private diagnostic\"}");
        var outcome = (ContractOutcome.NotCompleted<NarrationProposal>) run();
        assertThat(outcome.status()).isEqualTo(TaskResult.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("model-server-error");
        assertThat(server.chats()).hasSize(1);
    }
}
