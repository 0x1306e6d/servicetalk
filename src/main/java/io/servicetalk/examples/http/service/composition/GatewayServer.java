/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.examples.http.service.composition;

import io.servicetalk.client.api.ServiceDiscoverer;
import io.servicetalk.concurrent.api.CompositeCloseable;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.dns.discovery.netty.DefaultDnsServiceDiscovererBuilder;
import io.servicetalk.http.api.AggregatedHttpClient;
import io.servicetalk.http.api.HttpClient;
import io.servicetalk.http.api.HttpService;
import io.servicetalk.http.netty.DefaultHttpClientBuilder;
import io.servicetalk.http.netty.DefaultHttpServerStarter;
import io.servicetalk.http.router.predicate.HttpPredicateRouterBuilder;
import io.servicetalk.http.utils.HttpClientFunctionFilter;
import io.servicetalk.transport.api.DefaultExecutionContext;
import io.servicetalk.transport.api.ExecutionContext;
import io.servicetalk.transport.api.HostAndPort;
import io.servicetalk.transport.api.IoExecutor;
import io.servicetalk.transport.api.ServerContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

import static io.servicetalk.buffer.netty.BufferAllocators.DEFAULT_ALLOCATOR;
import static io.servicetalk.concurrent.api.AsyncCloseables.newCompositeCloseable;
import static io.servicetalk.concurrent.api.Executors.newCachedThreadExecutor;
import static io.servicetalk.concurrent.internal.Await.awaitIndefinitely;
import static io.servicetalk.concurrent.internal.Await.awaitIndefinitelyNonNull;
import static io.servicetalk.examples.http.service.composition.AsyncUtil.timeout;
import static io.servicetalk.examples.http.service.composition.backends.PortRegistry.METADATA_BACKEND_ADDRESS;
import static io.servicetalk.examples.http.service.composition.backends.PortRegistry.RATINGS_BACKEND_ADDRESS;
import static io.servicetalk.examples.http.service.composition.backends.PortRegistry.RECOMMENDATIONS_BACKEND_ADDRESS;
import static io.servicetalk.examples.http.service.composition.backends.PortRegistry.USER_BACKEND_ADDRESS;
import static io.servicetalk.loadbalancer.RoundRobinLoadBalancer.newRoundRobinFactory;
import static io.servicetalk.transport.netty.NettyIoExecutors.createIoExecutor;
import static java.time.Duration.ofMillis;

/**
 * A server starter for gateway to all backends.
 */
public final class GatewayServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayServer.class);

    private GatewayServer() {
        // No instances.
    }

    /**
     * Starts this server.
     *
     * @param args Program arguments, none supported yet.
     * @throws Exception If the server could not be started.
     */
    public static void main(String[] args) throws Exception {
        // Create an AutoCloseable representing all resources used in this example.
        try (CompositeCloseable resources = newCompositeCloseable()) {
            // Shared IoExecutor for the application.
            IoExecutor ioExecutor = createIoExecutor();
            // Add it as a resource to be cleaned up at the end.
            resources.concat(ioExecutor);
            DefaultHttpServerStarter starter = new DefaultHttpServerStarter(ioExecutor);
            starter.setWireLoggerName("gateway");
            final Executor executor = newCachedThreadExecutor();
            resources.concat(executor);
            ExecutionContext executionContext = new DefaultExecutionContext(DEFAULT_ALLOCATOR, ioExecutor, executor);
            // In this example we will use DNS as our Service Discovery system.
            ServiceDiscoverer<HostAndPort, InetSocketAddress> dnsDiscoverer =
                    new DefaultDnsServiceDiscovererBuilder(executionContext).build();

            // Use Jackson for serialization and deserialization.
            final ObjectMapper objectMapper = new ObjectMapper();
            // Create a ClientBuilder and use round robin load balancing.
            DefaultHttpClientBuilder<InetSocketAddress> clientBuilder = new DefaultHttpClientBuilder<>(newRoundRobinFactory());
            // Set retry and timeout filters for all clients.
            clientBuilder.setClientFilterFactory((client, lbEventStream) -> {
                // Apply a timeout filter for the client to guard against extremely latent clients.
                return new HttpClientFunctionFilter((requester, request) ->
                        timeout(requester.request(request), requester.getExecutionContext().getExecutor(), ofMillis(100)),
                        client);
            });

            // Create clients for the different backends we are going to use in the gateway.
            clientBuilder.setWireLoggerName("reco-client");
            HttpClient recommendationsClient = newClient(dnsDiscoverer, clientBuilder, ioExecutor, RECOMMENDATIONS_BACKEND_ADDRESS);
            clientBuilder.setWireLoggerName("meta-client");
            AggregatedHttpClient metadataClient = newAggregatedClient(dnsDiscoverer, clientBuilder, ioExecutor, METADATA_BACKEND_ADDRESS);
            clientBuilder.setWireLoggerName("user-client");
            AggregatedHttpClient userClient = newAggregatedClient(dnsDiscoverer, clientBuilder, ioExecutor, USER_BACKEND_ADDRESS);
            clientBuilder.setWireLoggerName("rating-client");
            AggregatedHttpClient ratingsClient = newAggregatedClient(dnsDiscoverer, clientBuilder, ioExecutor, RATINGS_BACKEND_ADDRESS);

            // Gateway supports different endpoints for blocking, streaming or aggregated implementations.
            // We create a router to express these endpoints.
            HttpPredicateRouterBuilder routerBuilder = new HttpPredicateRouterBuilder();
            final HttpService gatewayService =
                    routerBuilder.whenPathStartsWith("/recommendations/stream")
                            .thenRouteTo(new GatewayService(recommendationsClient, metadataClient, ratingsClient,
                                    userClient, objectMapper))
                            .whenPathStartsWith("/recommendations/aggregated")
                            .thenRouteTo(new AggregatedGatewayService(recommendationsClient.asAggregatedClient(),
                                    metadataClient, ratingsClient, userClient, objectMapper).asService())
                            .whenPathStartsWith("/recommendations/blocking")
                            .thenRouteTo(new BlockingGatewayService(recommendationsClient.asBlockingAggregatedClient(),
                                    metadataClient.asBlockingAggregatedClient(),
                                    ratingsClient.asBlockingAggregatedClient(),
                                    userClient.asBlockingAggregatedClient(), objectMapper).asService())
                            .build();

            // Starting the server will start listening for incoming client requests.
            ServerContext serverContext = awaitIndefinitelyNonNull(starter.start(8080, gatewayService));

            LOGGER.info("listening on {}", serverContext.getListenAddress());

            // Stop listening/accepting more sockets and gracefully shutdown all open sockets.
            awaitIndefinitely(serverContext.onClose());
        }
    }

    private static HttpClient newClient(ServiceDiscoverer<HostAndPort, InetSocketAddress> serviceDiscoverer,
                                        DefaultHttpClientBuilder<InetSocketAddress> clientBuilder,
                                        IoExecutor ioExecutor, final HostAndPort serviceAddress) {
        // Setup the ExecutionContext to offload user code onto a cached Executor.
        ExecutionContext executionContext =
                new DefaultExecutionContext(DEFAULT_ALLOCATOR, ioExecutor, newCachedThreadExecutor());
        return clientBuilder.build(executionContext, serviceDiscoverer.discover(serviceAddress));
    }

    private static AggregatedHttpClient newAggregatedClient(ServiceDiscoverer<HostAndPort, InetSocketAddress> serviceDiscoverer,
                                                            DefaultHttpClientBuilder<InetSocketAddress> clientBuilder,
                                                            IoExecutor ioExecutor, final HostAndPort serviceAddress) {
        // Setup the ExecutionContext to offload user code onto a cached Executor.
        ExecutionContext executionContext =
                new DefaultExecutionContext(DEFAULT_ALLOCATOR, ioExecutor, newCachedThreadExecutor());
        return clientBuilder.buildAggregated(executionContext, serviceDiscoverer.discover(serviceAddress));
    }
}
