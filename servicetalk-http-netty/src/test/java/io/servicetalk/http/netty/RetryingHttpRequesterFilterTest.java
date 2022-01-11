/*
 * Copyright © 2021 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.client.api.ConnectionFactory;
import io.servicetalk.client.api.DefaultAutoRetryStrategyProvider;
import io.servicetalk.client.api.DelegatingConnectionFactory;
import io.servicetalk.client.api.LoadBalancedConnection;
import io.servicetalk.client.api.LoadBalancer;
import io.servicetalk.client.api.LoadBalancerFactory;
import io.servicetalk.client.api.NoAvailableHostException;
import io.servicetalk.client.api.ServiceDiscovererEvent;
import io.servicetalk.concurrent.api.AsyncCloseables;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.CompositeCloseable;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.BlockingHttpClient;
import io.servicetalk.http.api.FilterableStreamingHttpConnection;
import io.servicetalk.http.api.SingleAddressHttpClientBuilder;
import io.servicetalk.http.utils.RetryingHttpRequesterFilter;
import io.servicetalk.loadbalancer.RoundRobinLoadBalancerFactory;
import io.servicetalk.transport.api.HostAndPort;
import io.servicetalk.transport.api.RetryableException;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.api.TransportObserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.Single.defer;
import static io.servicetalk.concurrent.api.Single.failed;
import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.http.netty.HttpClients.forResolvedAddress;
import static io.servicetalk.http.netty.HttpClients.forSingleAddress;
import static io.servicetalk.http.netty.HttpServers.forAddress;
import static io.servicetalk.http.netty.RetryingHttpRequesterFilter.BackOffPolicy.ofImmediate;
import static io.servicetalk.http.netty.RetryingHttpRequesterFilter.BackOffPolicy.ofNoRetries;
import static io.servicetalk.http.netty.RetryingHttpRequesterFilter.Builder;
import static io.servicetalk.http.netty.RetryingHttpRequesterFilter.HttpResponseException;
import static io.servicetalk.transport.netty.internal.AddressUtils.localAddress;
import static io.servicetalk.transport.netty.internal.AddressUtils.serverHostAndPort;
import static java.time.Duration.ofSeconds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

class RetryingHttpRequesterFilterTest {

    private static final String RETRYABLE_HEADER = "RETRYABLE";

    private final ServerContext svcCtx;
    private final SingleAddressHttpClientBuilder<HostAndPort, InetSocketAddress> normalClientBuilder;
    private final SingleAddressHttpClientBuilder<HostAndPort, InetSocketAddress> failingConnClientBuilder;
    private final SingleAddressHttpClientBuilder<HostAndPort, InetSocketAddress> noAvailableHostConnClientBuilder;
    private final AtomicInteger lbSelectInvoked;

    @Nullable
    private BlockingHttpClient normalClient;

    @Nullable
    private BlockingHttpClient failingClient;

    @Nullable
    private BlockingHttpClient noAvailableHostConnClient;

    RetryingHttpRequesterFilterTest() throws Exception {
        svcCtx = forAddress(localAddress(0))
                .listenBlockingAndAwait((ctx, request, responseFactory) -> responseFactory.ok()
                        .addHeader(RETRYABLE_HEADER, "yes"));
        failingConnClientBuilder = forSingleAddress(serverHostAndPort(svcCtx))
                .loadBalancerFactory(DefaultHttpLoadBalancerFactory.Builder
                        .from(new InspectingLoadBalancerFactory<>()).build())
                .appendConnectionFactoryFilter(ClosingConnectionFactory::new);
        noAvailableHostConnClientBuilder = forSingleAddress(serverHostAndPort(svcCtx))
                .loadBalancerFactory(DefaultHttpLoadBalancerFactory.Builder
                        .from(new InspectingLoadBalancerFactory<>()).build())
                .appendConnectionFactoryFilter(NAHConnectionFactory::new);
        normalClientBuilder = forSingleAddress(serverHostAndPort(svcCtx))
                .loadBalancerFactory(DefaultHttpLoadBalancerFactory.Builder
                        .from(new InspectingLoadBalancerFactory<>()).build());
        lbSelectInvoked = new AtomicInteger();
    }

    @AfterEach
    void tearDown() throws Exception {
        CompositeCloseable closeable = AsyncCloseables.newCompositeCloseable();
        if (normalClient != null) {
            closeable.append(normalClient.asClient());
        }
        if (failingClient != null) {
            closeable.append(failingClient.asClient());
        }
        if (noAvailableHostConnClient != null) {
            closeable.append(noAvailableHostConnClient.asClient());
        }
        closeable.append(svcCtx);
        closeable.close();
    }

    @Test
    void maxTotalRetries() {
        failingClient = failingConnClientBuilder
                .appendClientFilter(new Builder().maxTotalRetries(1).build())
                .buildBlocking();
        try {
            failingClient.request(failingClient.get("/"));
            fail("Request is expected to fail.");
        } catch (Exception e) {
            assertThat("Unexpected exception.", e, instanceOf(RetryableException.class));
            assertThat("Unexpected calls to select.", lbSelectInvoked.get(), is(2));
        }
    }

    @Test
    void requestRetryingPredicate() {
        failingClient = failingConnClientBuilder
                .appendClientFilter(new Builder()
                        .retryRetryableExceptions((requestMetaData, e) -> ofNoRetries())
                        .retryOther((requestMetaData, throwable) ->
                                requestMetaData.requestTarget().equals("/retry") ? ofImmediate() :
                                        ofNoRetries()).build())
                .buildBlocking();
        assertRequestRetryingPred(failingClient);
    }

    @Test
    void requestRetryingPredicateWithConditionalAppend() {
        noAvailableHostConnClient = noAvailableHostConnClientBuilder
                .appendClientFilter((__) -> true,
                        // Disable retryable exceptions, rely only on LB flow.
                        new Builder().retryRetryableExceptions((__, ___) -> ofNoRetries()).build())
                .buildBlocking();
        try {
            noAvailableHostConnClient.request(noAvailableHostConnClient.get("/"));
            fail("Request is expected to fail.");
        } catch (Exception e) {
            assertThat("Unexpected exception.", e, instanceOf(RetryableException.class));
            // 4 Retries
            assertThat("Unexpected calls to select.", lbSelectInvoked.get(), is(5));
        }
    }

    private void assertRequestRetryingPred(final BlockingHttpClient client) {
        try {
            client.request(client.get("/"));
            fail("Request is expected to fail.");
        } catch (Exception e) {
            assertThat("Unexpected exception.", e, instanceOf(RetryableException.class));
            // Account for LB readiness
            assertThat("Unexpected calls to select.", (double) lbSelectInvoked.get(), closeTo(1.0, 1.0));
        }

        try {
            client.request(client.get("/retry"));
            fail("Request is expected to fail.");
        } catch (Exception e) {
            assertThat("Unexpected exception.", e, instanceOf(RetryableException.class));
            // 1 Run + 3 Retries + 1 residual count from previous request + account for LB readiness
            assertThat("Unexpected calls to select.", (double) lbSelectInvoked.get(), closeTo(5.0, 1.0));
        }
    }

    @Test
    void responseRetryingPredicate() {
        normalClient = normalClientBuilder
                .appendClientFilter(new Builder()
                        .responseMapper(metaData -> metaData.headers().contains(RETRYABLE_HEADER) ?
                                    new HttpResponseException("Retryable header", metaData) : null)
                        // Disable request retrying
                        .retryRetryableExceptions((requestMetaData, e) -> ofNoRetries())
                        // Retry only responses marked so
                        .retryResponses((requestMetaData, throwable) -> ofImmediate())
                        .build())
                .buildBlocking();
        try {
            normalClient.request(normalClient.get("/"));
            fail("Request is expected to fail.");
        } catch (Exception e) {
            e.printStackTrace();
            assertThat("Unexpected exception.", e, instanceOf(HttpResponseException.class));
            assertThat("Unexpected calls to select.", lbSelectInvoked.get(), is(4));
        }
    }

    @Test
    void singleInstanceOldNew() {
        forResolvedAddress(localAddress(8888))
                .appendClientFilter(new RetryingHttpRequesterFilter.Builder()
                        .buildWithConstantBackoffFullJitter(ofSeconds(1)))
                .appendClientFilter(new Builder().build())
                .build();
    }

    @Test
    void singleInstanceNewOld() {
        forResolvedAddress(localAddress(8888))
                .appendClientFilter(new Builder().build())
                .appendClientFilter(new RetryingHttpRequesterFilter.Builder()
                        .buildWithConstantBackoffFullJitter(ofSeconds(1)))
                .build();
    }

    @Test
    void twoInstancesOld() {
        forResolvedAddress(localAddress(8888))
                .appendClientFilter(new RetryingHttpRequesterFilter.Builder()
                        .buildWithConstantBackoffFullJitter(ofSeconds(1)))
                .appendClientFilter(new RetryingHttpRequesterFilter.Builder()
                        .buildWithConstantBackoffFullJitter(ofSeconds(1)))
                .build();
    }

    @Test
    void twoInstancesNew() {
        Assertions.assertThrows(IllegalStateException.class, () -> forResolvedAddress(localAddress(8888))
                .appendClientFilter(new Builder().build())
                .appendClientFilter(new Builder().build())
                .build());
    }

    @Test
    void twoAutoRetryStrategies() {
        forResolvedAddress(localAddress(8888))
                .autoRetryStrategy(new DefaultAutoRetryStrategyProvider.Builder().maxRetries(3).build())
                .autoRetryStrategy(new DefaultAutoRetryStrategyProvider.Builder().build())
                .build();
    }

    @Test
    void singleInstanceNewFilterAndAutoRetry() {
        Assertions.assertThrows(IllegalStateException.class, () -> forResolvedAddress(localAddress(8888))
                .appendClientFilter(new Builder().build())
                .autoRetryStrategy(new DefaultAutoRetryStrategyProvider.Builder().build())
                .build());
    }

    @Test
    void singleInstanceAutoRetryAndNewFilter() {
        Assertions.assertThrows(IllegalStateException.class, () -> forResolvedAddress(localAddress(8888))
                .autoRetryStrategy(new DefaultAutoRetryStrategyProvider.Builder().build())
                .appendClientFilter(new Builder().build())
                .build());
    }

    @Test
    void singleInstanceAutoRetryAndOldFilter() {
        forResolvedAddress(localAddress(8888))
                .autoRetryStrategy(new DefaultAutoRetryStrategyProvider.Builder().build())
                .appendClientFilter(new RetryingHttpRequesterFilter.Builder()
                        .buildWithConstantBackoffFullJitter(ofSeconds(1)))
                .build();
    }

    @Test
    void singleInstanceOldFilterAndAutoRetry() {
        forResolvedAddress(localAddress(8888))
                .appendClientFilter(new RetryingHttpRequesterFilter.Builder()
                        .buildWithConstantBackoffFullJitter(ofSeconds(1)));
    }

    private final class InspectingLoadBalancerFactory<C extends LoadBalancedConnection>
            implements LoadBalancerFactory<InetSocketAddress, C> {

        private final LoadBalancerFactory<InetSocketAddress, C> rr =
                new RoundRobinLoadBalancerFactory.Builder<InetSocketAddress, C>().build();

        @Override
        public <T extends C> LoadBalancer<T> newLoadBalancer(
                final Publisher<? extends ServiceDiscovererEvent<InetSocketAddress>> eventPublisher,
                final ConnectionFactory<InetSocketAddress, T> connectionFactory) {
            return new InspectingLoadBalancer<>(rr.newLoadBalancer(eventPublisher, connectionFactory));
        }

        @Override
        public <T extends C> LoadBalancer<T> newLoadBalancer(
                final String targetResource,
                final Publisher<? extends Collection<? extends ServiceDiscovererEvent<InetSocketAddress>>>
                        eventPublisher,
                final ConnectionFactory<InetSocketAddress, T> connectionFactory) {
            return new InspectingLoadBalancer<>(rr.newLoadBalancer(targetResource, eventPublisher, connectionFactory));
        }
    }

    private final class InspectingLoadBalancer<C extends LoadBalancedConnection> implements LoadBalancer<C> {
        private final LoadBalancer<C> delegate;

        private InspectingLoadBalancer(final LoadBalancer<C> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Single<C> selectConnection(final Predicate<C> selector) {
            return defer(() -> {
                lbSelectInvoked.incrementAndGet();
                return delegate.selectConnection(selector);
            });
        }

        @Override
        public Publisher<Object> eventStream() {
            return delegate.eventStream();
        }

        @Override
        public Completable onClose() {
            return delegate.onClose();
        }

        @Override
        public Completable closeAsync() {
            return delegate.closeAsync();
        }

        @Override
        public Completable closeAsyncGracefully() {
            return delegate.closeAsyncGracefully();
        }
    }

    private static final class ClosingConnectionFactory
            extends DelegatingConnectionFactory<InetSocketAddress, FilterableStreamingHttpConnection> {
        ClosingConnectionFactory(
                final ConnectionFactory<InetSocketAddress, FilterableStreamingHttpConnection> original) {
            super(original);
        }

        @Override
        public Single<FilterableStreamingHttpConnection> newConnection(final InetSocketAddress inetSocketAddress,
                                                                       @Nullable final TransportObserver observer) {
            return delegate().newConnection(inetSocketAddress, observer)
                    .flatMap(c -> c.closeAsync().concat(succeeded(c)));
        }
    }

    private static final class NAHConnectionFactory
            extends DelegatingConnectionFactory<InetSocketAddress, FilterableStreamingHttpConnection> {
        NAHConnectionFactory(
                final ConnectionFactory<InetSocketAddress, FilterableStreamingHttpConnection> original) {
            super(original);
        }

        @Override
        public Single<FilterableStreamingHttpConnection> newConnection(final InetSocketAddress inetSocketAddress,
                                                                       @Nullable final TransportObserver observer) {
            return delegate().newConnection(inetSocketAddress, observer)
                    .flatMap(c -> c.closeAsync().concat(failed(new NoAvailableHostException(""))));
        }
    }
}
