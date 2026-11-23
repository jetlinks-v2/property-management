package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetlinks.core.event.*;
import org.jetlinks.core.rpc.RpcManager;
import org.jetlinks.pro.cluster.*;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;
import org.jetlinks.pro.test.utils.Clusters;
import org.jetlinks.pro.topic.Topics;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

/** 真实双节点 RPC 总线验证，不能用前端订阅提供者 mock 代替后端传输。 */
class FacilityAlarmTopicsTest {
  @Test void backendRecordLogAndRelieveTravelAcrossNodesWithoutBusinessNamespaceOrOtherDeviceLeakage() {
    var nodes = Clusters.create(2);
    var sourceCluster = new ClusterManagerImpl(nodes.getNode(0).getRpc(), nodes.getNode(0).getNode());
    var applicationCluster = new ClusterManagerImpl(nodes.getNode(1).getRpc(), nodes.getNode(1).getNode());
    var source = new ClusterEventBus("_default", nodes.getNode(0).getRpc(), sourceCluster);
    var business = new ClusterEventBus("property-facility-energy", nodes.getNode(1).getRpc(), applicationCluster);
    var topics = new FacilityAlarmTopics(business, nodes.getNode(1).getRpc(),
      applicationCluster, "_default", "property-facility-energy");
    var seen = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    var emissions = new java.util.concurrent.CopyOnWriteArrayList<CloudAlarmRecord>();
    var published = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var stream = topics.watch(Set.of("meter"));
    // 只在订阅传播窗口内重发相同信号；有限次数，无阻塞等待，接收去重仍核对三类真实 Topic。
    var publishing = Flux.range(0, 40).concatMap(tick -> Mono.delay(Duration.ofMillis(200))
      .then(source.publish(Topics.alarm("device", "meter", "rule"), signal("record", "meter")).doOnSuccess(n -> published.add("record:" + n)))
        .then(source.publish(Topics.alarmLog("device", "meter", "rule", "history"), signal("log", "meter")).doOnSuccess(n -> published.add("log:" + n)))
        .then(source.publish(Topics.alarmRelieve("device", "meter", "rule"), signal("relieve", "meter")).doOnSuccess(n -> published.add("relieve:" + n)))
        .then(source.publish(Topics.alarm("device", "other", "rule"), signal("foreign-device", "other")))
        .then(business.publish(Topics.alarm("device", "meter", "rule"), signal("business-only", "meter"))));
    var sender = new reactor.core.Disposable[1];
    try {
      StepVerifier.create(stream.doOnNext(emissions::add).filter(row -> seen.add(row.getId())).take(3))
        .then(() -> sender[0] = publishing.subscribe(n -> {}, e -> published.add("error:" + e)))
        .expectNextCount(3)
        .expectComplete().verify(Duration.ofSeconds(20));
      assertTrue(emissions.stream().allMatch(row -> row.belongsTo("meter")));
      assertEquals(Set.of("record", "log", "relieve"), seen);
    } catch (AssertionError error) {
      throw new AssertionError("Received alarm IDs: " + emissions.stream().map(CloudAlarmRecord::getId).toList() + "; published: " + published, error);
    } finally {
      if (sender[0] != null) sender[0].dispose();
      topics.close(); business.shutdown(); source.shutdown(); nodes.shutdown();
    }
  }

  @Test void portableAlarmPayloadProjectsDeviceAndRecordIdentity() {
    var safe = org.jetlinks.core.utils.SerializeUtils.convertToSafelySerializable(signal("record", "meter"), true);
    assertInstanceOf(Map.class, safe);
    var row = org.hswebframework.web.bean.FastBeanCopier.copy(safe, new CloudAlarmRecord());
    assertEquals("record", row.getId());
    assertTrue(row.belongsTo("meter"));
    assertEquals("rule", row.getAlarmConfigId());
    assertEquals(1000L, row.getAlarmTime());
  }

  @Test void sharedNamespaceUsesInjectedFactoryWithoutRegisteringAnotherRpcBus() {
    var shared = mock(EventBus.class);
    when(shared.subscribe(any(Subscription.class), eq(CloudAlarmRecord.class))).thenReturn(Flux.never());
    var beanFactory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    var definition = new org.springframework.beans.factory.support.RootBeanDefinition();
    definition.setTargetType(org.springframework.core.ResolvableType.forClassWithGenerics(
      java.util.function.Function.class, String.class, EventBus.class));
    definition.setInstanceSupplier(() -> (java.util.function.Function<String, EventBus>) name -> shared);
    beanFactory.registerBeanDefinition("factory", definition);
    var rpc = mock(RpcManager.class);
    var cluster = mock(ClusterManager.class);
    var topics = new FacilityAlarmTopics(mock(EventBus.class), rpc, cluster, "_default", "business",
      beanFactory.getBeanProvider(org.springframework.core.ResolvableType.forClassWithGenerics(
        java.util.function.Function.class, String.class, EventBus.class)));
    StepVerifier.create(topics.watch(Set.of("meter"))).thenCancel().verify();
    topics.close();
    verify(shared).subscribe(any(Subscription.class), eq(CloudAlarmRecord.class));
    verifyNoInteractions(rpc, cluster);
  }

  @Test void sameNamespaceReusesPrimaryBusAndNeverShutsItDown() {
    var bus = mock(EventBus.class);
    when(bus.subscribe(any(Subscription.class), eq(CloudAlarmRecord.class))).thenReturn(Flux.never());
    var topics = new FacilityAlarmTopics(bus, mock(RpcManager.class), mock(ClusterManager.class), "_default", "_default");
    StepVerifier.create(topics.watch(Set.of("meter"))).thenCancel().verify();
    var capture = org.mockito.ArgumentCaptor.forClass(Subscription.class);
    verify(bus).subscribe(capture.capture(), eq(CloudAlarmRecord.class));
    assertTrue(capture.getValue().hasFeature(Subscription.Feature.safetySerialization),
      "SaaS internal entities must cross the boundary as portable values");
    assertEquals(Set.of(Topics.alarm("device", "meter", "*"), Topics.alarmLog("device", "meter", "*", "*"),
      Topics.alarmRelieve("device", "meter", "*")), new HashSet<>(Arrays.asList(capture.getValue().getTopics())));
    topics.close(); verifyNoMoreInteractions(bus);
  }

  @Test void emptySelectionNeverSubscribesAndWildcardDeviceIdsAreRejected() {
    var bus = mock(EventBus.class);
    var topics = new FacilityAlarmTopics(bus, mock(RpcManager.class), mock(ClusterManager.class), "_default", "_default");
    StepVerifier.create(topics.watch(Set.of())).verifyComplete();
    for (var id : List.of("*", "a/b", " ", "#")) StepVerifier.create(topics.watch(Set.of(id))).expectError().verify();
    verifyNoInteractions(bus);
  }

  @Test void queueOverflowFailsVisiblyAndCancelsUpstream() {
    var bus = mock(EventBus.class);
    var signals = Sinks.many().multicast().<CloudAlarmRecord>directBestEffort();
    when(bus.subscribe(any(Subscription.class), eq(CloudAlarmRecord.class))).thenReturn(signals.asFlux());
    var topics = new FacilityAlarmTopics(bus, mock(RpcManager.class), mock(ClusterManager.class), "_default", "_default");
    StepVerifier.create(topics.watch(Set.of("meter")), 0)
      .then(() -> {
        for (int i = 0; i <= 256; i++) signals.tryEmitNext(new CloudAlarmRecord());
      }).thenRequest(256).expectNextCount(256)
      .expectErrorMatches(reactor.core.Exceptions::isOverflow).verify(Duration.ofSeconds(5));
    assertEquals(0, signals.currentSubscriberCount());
  }

  private static AlarmPayload signal(String id, String device) {
    return new AlarmPayload(id, "device", device, "rule", 1000L);
  }

  /** 模拟发送端私有 JavaBean，保留安全序列化覆盖，接收端只依赖字段契约。 */
  @Getter
  @RequiredArgsConstructor
  public static class AlarmPayload {
    private final String id;
    private final String targetType;
    private final String targetId;
    private final String alarmConfigId;
    private final long alarmTime;
  }
}
