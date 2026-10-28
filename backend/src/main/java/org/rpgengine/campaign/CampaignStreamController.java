package org.rpgengine.campaign;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.rpgengine.commands.CampaignCommandJson;
import org.rpgengine.commands.OperationAdmissionFailure;
import org.rpgengine.commands.PublicStreamService;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Server-Sent Events adapter of the resumable public operation stream (P07.5, docs/13 section 4).
 * {@code GET /api/v1/campaigns/{campaignId}/stream?adventureId=...} resumes after the
 * {@code Last-Event-ID} header that browsers send on reconnect, or the {@code after} query
 * parameter on a first connection; without either it starts at the head with
 * {@code stream.ready}. Each response is a bounded long poll: it writes every available message,
 * waits up to the configured hold time for more (polling durable storage, never a callback), then
 * ends so the client reconnects with its last identity. The stream is delivery only: it accepts no
 * input, submits nothing on reconnect and changes no row.
 *
 * <p>Errors detected before the first byte (scope, malformed cursor, capacity, storage) return
 * the usual JSON codes; a failure after streaming began just ends the response, and the client
 * resumes from its last identity. At most eight streams run concurrently, bounding held threads.
 */
@RestController
public class CampaignStreamController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_STREAMS = 8;
    private static final long HEARTBEAT_MILLIS = 5_000;
    private final PublicStreamService stream;
    private final long holdMillis;
    private final long pollMillis;
    private final Semaphore streams = new Semaphore(MAX_STREAMS);

    /**
     * Configure the long-poll window; invalid timing fails startup.
     *
     * @param stream read side of the durable stream
     * @param holdMillis how long one response waits for new messages, 0..60000 milliseconds
     * @param pollMillis storage polling interval while holding, 20..5000 milliseconds
     */
    public CampaignStreamController(PublicStreamService stream,
            @Value("${engine.stream.hold-ms:15000}") long holdMillis,
            @Value("${engine.stream.poll-ms:250}") long pollMillis) {
        this.stream = java.util.Objects.requireNonNull(stream, "stream");
        if (holdMillis < 0 || holdMillis > 60_000 || pollMillis < 20 || pollMillis > 5_000) {
            throw new IllegalArgumentException("stream hold must be 0..60000 ms and poll 20..5000 ms");
        }
        this.holdMillis = holdMillis;
        this.pollMillis = pollMillis;
    }

    /**
     * Stream messages after the client's cursor as {@code text/event-stream}.
     *
     * @param request admitted HTTP request carrying the owner attribute
     * @param response servlet response written incrementally
     * @param campaignId canonical campaign UUID
     * @param adventureId canonical adventure UUID that must own the campaign
     * @param after optional cursor for a first connection; {@code Last-Event-ID} takes precedence
     * @throws IOException only if writing a pre-stream JSON error fails
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/stream")
    public void stream(HttpServletRequest request, HttpServletResponse response, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId, @RequestParam(required = false) String after)
            throws IOException {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            error(response, 403, "LOCAL_ADMISSION_DENIED");
            return;
        }
        UUID campaign;
        UUID adventure;
        String cursor;
        try {
            campaign = CampaignCommandJson.uuid(campaignId);
            adventure = CampaignCommandJson.uuid(adventureId);
            var headers = request.getHeaders("Last-Event-ID");
            String header = headers.hasMoreElements() ? headers.nextElement() : null;
            // Two cursors are ambiguous; never pick one by header order. The sentinel is malformed.
            cursor = headers.hasMoreElements() ? "" : header != null ? header : after;
        } catch (CampaignCommandJson.Malformed failure) {
            error(response, 400, "COMMAND_INPUT_MALFORMED");
            return;
        }
        if (!streams.tryAcquire()) {
            error(response, 429, "STREAM_CAPACITY_EXCEEDED");
            return;
        }
        try {
            PublicStreamService.Batch first;
            try {
                first = stream.read(owner, adventure, campaign, cursor);
            } catch (CampaignCommandJson.Malformed failure) {
                error(response, 400, "COMMAND_INPUT_MALFORMED");
                return;
            } catch (OperationAdmissionFailure failure) {
                error(response, 404, "COMMAND_SCOPE_UNAVAILABLE");
                return;
            } catch (DataAccessException | TransactionException | IllegalStateException failure) {
                error(response, 503, "COMMAND_STORAGE_UNAVAILABLE");
                return;
            }
            response.setStatus(200);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setHeader("X-Accel-Buffering", "no");
            OutputStream out = response.getOutputStream();
            out.write("retry: 1000\n\n".getBytes(StandardCharsets.UTF_8));
            String position = write(out, first, cursor);
            long deadline = System.nanoTime() + holdMillis * 1_000_000;
            long quietSince = System.nanoTime();
            while (System.nanoTime() < deadline) {
                Thread.sleep(pollMillis);
                var next = stream.read(owner, adventure, campaign, position);
                String moved = write(out, next, position);
                if (!java.util.Objects.equals(moved, position)) {
                    position = moved;
                    quietSince = System.nanoTime();
                } else if (System.nanoTime() - quietSince > HEARTBEAT_MILLIS * 1_000_000) {
                    // A comment line detects a closed connection without delivering a message.
                    out.write(":\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    quietSince = System.nanoTime();
                }
            }
        } catch (IOException | RuntimeException failure) {
            // The client disconnected or storage failed mid-stream; it resumes from its last identity.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            streams.release();
        }
    }

    /**
     * Write one batch as SSE frames and flush.
     *
     * @return the identity to resume from: the batch's last identity, or {@code position} when empty
     */
    private static String write(OutputStream out, PublicStreamService.Batch batch, String position) throws IOException {
        for (var message : batch.messages()) {
            String frame = "id: " + message.id() + "\nevent: " + message.type() + "\ndata: "
                    + JSON.writeValueAsString(message.data()) + "\n\n";
            out.write(frame.getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
        return batch.last() == null ? position : batch.last();
    }

    /** Write one fixed JSON error code before any stream byte; never echo input or diagnostics. */
    private static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(JSON.writeValueAsString(Map.of("code", code)));
    }
}
