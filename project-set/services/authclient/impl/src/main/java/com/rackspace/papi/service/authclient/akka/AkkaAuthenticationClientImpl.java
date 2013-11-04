package com.rackspace.papi.service.authclient.akka;


import akka.actor.*;
import akka.routing.RoundRobinRouter;
import akka.util.Timeout;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.rackspace.papi.commons.util.http.ServiceClient;
import com.rackspace.papi.commons.util.http.ServiceClientResponse;
import com.rackspace.papi.service.httpclient.HttpClientService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static akka.pattern.Patterns.ask;

public class AkkaAuthenticationClientImpl implements AkkaAuthenticationClient {

    final private ServiceClient serviceClient;
    private ActorSystem actorSystem;
    private ActorRef tokenActorRef;
    private int numberOfActors;
    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(AkkaAuthenticationClientImpl.class);
    final Timeout t = new Timeout(50, TimeUnit.SECONDS);
    private final Cache<String, Future> quickFutureCache;

    private static final long FUTURE_CACHE_TTL = 500;
    private static final long MAX_FUTURE_CACHE_SIZE = 1000;  // just guessing on this

    @Autowired
    public AkkaAuthenticationClientImpl(HttpClientService httpClientService) {
        this.serviceClient = getServiceClient(httpClientService);
        numberOfActors = serviceClient.getPoolSize();

        Config customConf = ConfigFactory.parseString(
                "akka { actor { default-dispatcher {throughput = 10} } }");
        Config regularConf = ConfigFactory.defaultReference();
        Config combinedConf = customConf.withFallback(regularConf);

        actorSystem = ActorSystem.create("AuthClientActors", ConfigFactory.load(combinedConf));

        quickFutureCache = CacheBuilder.newBuilder()
                .expireAfterWrite(FUTURE_CACHE_TTL, TimeUnit.MILLISECONDS)
                .build();

        tokenActorRef = actorSystem.actorOf(new Props(new UntypedActorFactory() {
            public UntypedActor create() {
                return new AuthTokenFutureActor(serviceClient);
            }
        }).withRouter(new RoundRobinRouter(numberOfActors)), "authRequestRouter");
    }


    @Override
    public ServiceClientResponse get(String key, String uri, Map<String, String> headers) {

        ServiceClientResponse reusableServiceserviceClientResponse = null;
        AuthGetRequest authGetRequest = new AuthGetRequest(key, uri, headers);
        Future<ServiceClientResponse> future = getFuture(authGetRequest);
        try {
            reusableServiceserviceClientResponse = Await.result(future, Duration.create(50, TimeUnit.SECONDS));
        } catch (Exception e) {
            LOG.error("error with akka future: " + e.getMessage());
        }
        return reusableServiceserviceClientResponse;
    }

    @Override
    public void shutdown(){
        actorSystem.shutdown();

    }

    public Future getFuture(AuthGetRequest authGetRequest) {
        String token = authGetRequest.hashKey();
        Future<Object> newFuture;
        if (!quickFutureCache.asMap().containsKey(token)) {
            synchronized (quickFutureCache) {
                if (!quickFutureCache.asMap().containsKey(token)) {
                    newFuture = ask(tokenActorRef, authGetRequest, t);
                    quickFutureCache.asMap().putIfAbsent(token, newFuture);
                }
            }
        }
        return quickFutureCache.asMap().get(token);
    }

    public ServiceClient getServiceClient(HttpClientService httpClientService){
        return new ServiceClient(null,httpClientService);
    }
}
