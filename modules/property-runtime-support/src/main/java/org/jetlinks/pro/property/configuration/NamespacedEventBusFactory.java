package org.jetlinks.pro.property.configuration;

import org.jetlinks.core.event.EventBus;
import org.jetlinks.core.rpc.RpcManager;
import org.jetlinks.pro.cluster.ClusterEventBus;
import org.jetlinks.pro.cluster.ClusterManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 同进程各业务通过 namespacedEventBusFactory 限定名的 Function 共享命名空间总线。
 * 同一 RPC 命名空间只能注册一次；主业务总线不重复创建，专用总线由本组件统一释放。
 * 不将专用总线暴露为 EventBus Bean，避免改变已有业务总线注入；监控沿用 ClusterEventBus MBean。
 */
@Component("namespacedEventBusFactory")
public class NamespacedEventBusFactory implements Function<String, EventBus>, DisposableBean {
    private final EventBus business;
    private final RpcManager rpc;
    private final ClusterManager cluster;
    private final String businessNamespace;
    private final Map<String, ClusterEventBus> owned = new HashMap<>();
    private boolean closed;

    public NamespacedEventBusFactory(EventBus business, RpcManager rpc, ClusterManager cluster,
            @Value("${jetlinks.cluster.event-namespace:_default}") String businessNamespace) {
        this.business = business;
        this.rpc = rpc;
        this.cluster = cluster;
        this.businessNamespace = businessNamespace;
    }

    @Override
    public synchronized EventBus apply(String namespace) {
        if (closed) throw new IllegalStateException("Event bus factory is closed");
        if (Objects.equals(businessNamespace, namespace)) return business;
        return owned.computeIfAbsent(Objects.requireNonNull(namespace),
                name -> new ClusterEventBus(name, rpc, cluster));
    }

    @Override
    public synchronized void destroy() {
        closed = true;
        owned.values().forEach(ClusterEventBus::shutdown);
        owned.clear();
    }
}
