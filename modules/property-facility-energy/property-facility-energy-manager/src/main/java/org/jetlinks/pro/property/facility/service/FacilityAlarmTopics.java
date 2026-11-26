package org.jetlinks.pro.property.facility.service;

import java.util.*;
import org.jetlinks.core.event.*;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;
import org.jetlinks.pro.topic.Topics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 后端告警总线适配器。外部 /record 由平台映射到 Topics.alarm；不用 WebSocket 提供者解析后端 Topic。
 * 复用平台装配的 EventBus，集群订阅仅接收同命名空间的 SaaS 告警。
 */
@Component
public class FacilityAlarmTopics {
  private final EventBus eventBus;
  private final boolean realtimeAvailable;

  public FacilityAlarmTopics(EventBus eventBus,
      @Value("${facility.saas-alarm.event-namespace:_default}") String alarmNamespace,
      @Value("${jetlinks.cluster.event-namespace:_default}") String busNamespace) {
    this.eventBus = eventBus;
    this.realtimeAvailable = Objects.equals(alarmNamespace, busNamespace);
  }

  boolean isRealtimeAvailable() { return realtimeAvailable; }

  /** 设备集合由调用方在当前身份/应用资产范围内解析；每条消息仍须回查绑定与授权后才落库。 */
  public Flux<CloudAlarmRecord> watch(Set<String> devices) {
    return Flux.defer(() -> {
      if (devices.isEmpty()) return Flux.empty();
      var topics = new LinkedHashSet<String>();
      for (var id : devices) {
        // 资产 ID 必须是精确路径段，拒绝把坏数据变成跨设备通配订阅。
        FacilityScope.require(id != null && !id.isBlank() && !id.matches(".*[/+#*].*"), "invalid_alarm_source");
        topics.add(Topics.alarm("device", id, "*"));
        topics.add(Topics.alarmLog("device", id, "*", "*"));
        topics.add(Topics.alarmRelieve("device", id, "*"));
      }
      return eventBus.subscribe(Subscription.of("facility-saas-alarm-" + UUID.randomUUID(), topics.toArray(String[]::new),
          // SaaS 的内部告警实体须转为可移植值，避免接收端依赖规则引擎实现类。
          Subscription.Feature.local, Subscription.Feature.broker, Subscription.Feature.safetySerialization), CloudAlarmRecord.class)
        // 溢出显式终止并触发重订阅/补查，不能静默丢告警或无限积压。
        .onBackpressureBuffer(256);
    });
  }

}
