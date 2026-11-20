package org.jetlinks.pro.property;

import org.jetlinks.core.event.Subscription;
import org.jetlinks.pro.cluster.ClusterEventBus;
import org.jetlinks.pro.cluster.ClusterManagerImpl;
import org.jetlinks.pro.property.configuration.NamespacedEventBusFactory;
import org.jetlinks.pro.test.utils.Clusters;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** 同一真实 RPC 节点的多个调用方只注册一个 namespace；只释放自己拥有的总线。 */
class NamespacedEventBusFactoryTest {
    @Test
    void consumersShareNamespaceAndStopWithoutClosingPrimaryBus() {
        var nodes = Clusters.create(1);
        var cluster = new ClusterManagerImpl(nodes.getNode(0).getRpc(), nodes.getNode(0).getNode());
        var business = new ClusterEventBus("business", nodes.getNode(0).getRpc(), cluster);
        var factory = new NamespacedEventBusFactory(business, nodes.getNode(0).getRpc(), cluster, "business");
        try {
            assertSame(business, factory.apply("business"));
            var first = factory.apply("source");
            assertSame(first, factory.apply("source"));
            assertNotSame(first, factory.apply("another-source"));
            StepVerifier.create(first.subscribe(Subscription.of("one", new String[]{"/alarm/*"},
                    Subscription.Feature.local), String.class).take(1))
                .then(() -> first.publish("/alarm/device", "received").block(Duration.ofSeconds(5)))
                .expectNext("received").verifyComplete();
            factory.destroy();
            factory.destroy();
            assertThrows(IllegalStateException.class, () -> factory.apply("source"));
            StepVerifier.create(business.subscribe(Subscription.of("primary", new String[]{"/still-alive"},
                    Subscription.Feature.local), String.class).take(1))
                .then(() -> business.publish("/still-alive", "ok").block(Duration.ofSeconds(5)))
                .expectNext("ok").verifyComplete();
        } finally {
            factory.destroy(); business.shutdown(); nodes.shutdown();
        }
    }
}
