package com.smartapi.gateway.redis;

import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.service.RegistryChangedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Cross-gateway cache invalidation. Phase 2's discovery cache was cleared only in the gateway that
 * made the registry change; other gateways kept routing on stale data for up to the TTL.
 * Now every committed change is also PUBLISHED on a Redis channel, and every gateway (including this
 * one) that receives it clears its discovery cache.
 *
 * <p>Pub/sub is fire-and-forget: a gateway disconnected from Redis misses the message. That's why the
 * TTL stays in place as the safety net. The design is "fast path: push; guarantee: expiry".
 */
@Component
public class RegistryChangeBroadcaster implements MessageListener {

    public static final String CHANNEL = "gateway:registry-changes";
    private static final Logger log = LoggerFactory.getLogger(RegistryChangeBroadcaster.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final ServiceDiscovery discovery;
    private final String gatewayId = UUID.randomUUID().toString();

    public RegistryChangeBroadcaster(StringRedisTemplate redis, RedisGuard guard, ServiceDiscovery discovery) {
        this.redis = redis;
        this.guard = guard;
        this.discovery = discovery;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(RegistryChangedEvent event) {
        if (!guard.run(() -> redis.convertAndSend(CHANNEL, gatewayId + "|" + event.reason()))) {
            log.debug("Could not broadcast registry change; other gateways will refresh after their cache TTL");
        }
    }

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        String body = new String(message.getBody());
        if (!body.startsWith(gatewayId + "|")) {      // our own change was already applied locally
            log.debug("Registry changed on another gateway ({}); clearing discovery cache", body);
            discovery.invalidateAll();
        }
    }
}
