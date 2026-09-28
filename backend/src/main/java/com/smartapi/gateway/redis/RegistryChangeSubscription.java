package com.smartapi.gateway.redis;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the registry-change subscription alive without making Redis a startup dependency.
 *
 * <p>The listener container is deliberately NOT a Spring bean: Spring would start it with the
 * application context, and starting subscribes synchronously, so if Redis were down the whole gateway
 * would fail to start (found by the integration tests). Instead we try to start it right after
 * startup and then every 5 s until it runs; once running, the container recovers from dropped
 * connections by itself.
 */
@Component
public class RegistryChangeSubscription {

    private static final Logger log = LoggerFactory.getLogger(RegistryChangeSubscription.class);

    private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    private boolean warned;

    public RegistryChangeSubscription(RedisConnectionFactory connectionFactory, RegistryChangeBroadcaster broadcaster) {
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broadcaster, new ChannelTopic(RegistryChangeBroadcaster.CHANNEL));
        container.setRecoveryInterval(5000);
        container.afterPropertiesSet();
    }

    @Scheduled(initialDelay = 0, fixedDelay = 5000)
    public synchronized void ensureSubscribed() {
        if (container.isRunning()) {
            return;
        }
        try {
            container.start();
            log.info("Subscribed to registry changes on Redis channel {}", RegistryChangeBroadcaster.CHANNEL);
            warned = false;
        } catch (RuntimeException e) {
            if (!warned) {
                log.warn("Redis not reachable; registry-change broadcasts disabled until it is ({})", e.getMessage());
                warned = true;
            }
            try {
                container.stop();   // back to a startable state for the next attempt
            } catch (RuntimeException ignored) {
                // nothing to stop
            }
        }
    }

    public boolean isSubscribed() {
        return container.isRunning();
    }

    @PreDestroy
    public void shutdown() throws Exception {
        container.destroy();
    }
}
