package org.rpgengine.inference.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Collects one response body up to a fixed size. A longer body cancels the exchange and fails the body
 * stage with {@link TooLarge}, so a misbehaving server cannot grow the engine's heap. It mirrors the
 * subscriber of the local text adapter, because adapter packages may not import each other.
 */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    /** The response body passed the limit. */
    static final class TooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        TooLarge() {
            super("response body too large");
        }
    }

    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    /** @param limit largest accepted body in bytes */
    BoundedBody(int limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        for (ByteBuffer item : items) {
            if (body.isDone()) {
                return;
            }
            if (item.remaining() > limit - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(new TooLarge());
                return;
            }
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            bytes.writeBytes(chunk);
        }
    }

    @Override
    public void onError(Throwable failure) {
        body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        body.complete(bytes.toByteArray());
    }
}
