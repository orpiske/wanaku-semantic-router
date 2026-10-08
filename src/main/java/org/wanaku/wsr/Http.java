package org.wanaku.wsr;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    static final int MAX_RESPONSE_BYTES = 48 * 1024 * 1024;

    private Http() {}

    static HttpRequest.Builder request(URI uri, String token) {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    static byte[] send(HttpRequest request) throws IOException, InterruptedException {
        var response = exchange(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP operation failed with status " + response.statusCode());
        }
        return response.body();
    }

    static HttpResponse<byte[]> exchange(HttpRequest request) throws IOException, InterruptedException {
        return exchange(request, MAX_RESPONSE_BYTES);
    }

    static HttpResponse<byte[]> exchange(HttpRequest request, int maxBytes) throws IOException, InterruptedException {
        return exchange(request, HttpResponse.BodyHandlers.ofByteArray(), maxBytes);
    }

    private static <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler, int maxBytes)
            throws IOException, InterruptedException {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Invalid response size limit");
        }
        var body = new LimitedBodySubscriber<T>(maxBytes);
        // The completion includes response-body receipt. HttpRequest.timeout alone can end at headers.
        long deadline = System.nanoTime()
                + request.timeout().orElse(Duration.ofSeconds(30)).toNanos();
        var operation = CLIENT.sendAsync(request, info -> body.initialize(handler.apply(info)));
        try {
            long remaining = Math.max(1, deadline - System.nanoTime());
            return operation.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            var timeout = new HttpTimeoutException("HTTP response completion timed out");
            // Abort the HTTP exchange before completing the body stage makes its future terminal.
            operation.cancel(true);
            body.cancel(timeout);
            throw timeout;
        } catch (InterruptedException e) {
            operation.cancel(true);
            body.cancel(new IOException("HTTP operation interrupted"));
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("HTTP operation failed", e.getCause());
        }
    }

    /** Uses the existing bounded exchange for synchronous SDK JSON requests. */
    static HttpClient sdkClient() {
        return sdkClient(MAX_RESPONSE_BYTES);
    }

    static HttpClient sdkClient(int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Invalid response size limit");
        }
        return new SdkClient(maxBytes);
    }

    private static final class SdkClient extends HttpClient {
        private final int maxBytes;

        private SdkClient(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            var response = exchange(request, handler, maxBytes);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP operation failed with status " + response.statusCode());
            }
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("SDK transport requires synchronous requests"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return CLIENT.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return CLIENT.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return CLIENT.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return CLIENT.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return CLIENT.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return CLIENT.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return CLIENT.authenticator();
        }

        @Override
        public Version version() {
            return CLIENT.version();
        }

        @Override
        public Optional<Executor> executor() {
            return CLIENT.executor();
        }
    }

    /** Cancels before forwarding bytes beyond the configured cap to the response decoder. */
    private static final class LimitedBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final int maxBytes;
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private HttpResponse.BodySubscriber<T> delegate;
        private Flow.Subscription subscription;
        private Throwable failure;
        private int size;
        private boolean finished;

        private LimitedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        private synchronized LimitedBodySubscriber<T> initialize(HttpResponse.BodySubscriber<T> value) {
            delegate = value;
            value.getBody().whenComplete((result, error) -> {
                if (error != null) {
                    completion.completeExceptionally(error);
                } else {
                    completion.complete(result);
                }
            });
            if (failure != null) {
                value.onError(failure);
            }
            return this;
        }

        @Override
        public CompletionStage<T> getBody() {
            return completion;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription value) {
            if (subscription != null || finished) {
                value.cancel();
                return;
            }
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> items) {
            if (finished) {
                return;
            }
            for (ByteBuffer buffer : items) {
                if (buffer.remaining() > maxBytes - size) {
                    cancel(new IOException("HTTP response exceeds size limit"));
                    return;
                }
                size += buffer.remaining();
            }
            delegate.onNext(items);
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (!finished) {
                finished = true;
                failure = error;
                completion.completeExceptionally(error);
                if (delegate != null) {
                    delegate.onError(error);
                }
            }
        }

        @Override
        public synchronized void onComplete() {
            if (!finished) {
                finished = true;
                delegate.onComplete();
            }
        }

        private synchronized void cancel(IOException error) {
            if (finished) {
                return;
            }
            // Claim the terminal state before upstream cancellation can call back into this subscriber.
            finished = true;
            failure = error;
            if (subscription != null) {
                subscription.cancel();
            }
            completion.completeExceptionally(error);
            if (delegate != null) {
                delegate.onError(error);
            }
        }
    }
}
