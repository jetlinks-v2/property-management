package org.jetlinks.pro.property.facility.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.metadata.*;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.core.things.ThingMetadata;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.impl.terms.*;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.property.facility.command.QueryFacilityPropertyPageCommand;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.jetlinks.sdk.server.device.*;
import org.jetlinks.sdk.server.device.cmd.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** Cloud公开SDK适配器；应用或组织范围继续与调用人device资产取交集，不返回连接配置或密钥。 */
@Service
@RequiredArgsConstructor
public class FacilityCloud {

  private final FacilityScope scope;

  public Mono<PagerResult<DeviceInfo>> page(
    String projectId,
    int page,
    int size
  ) {
    return page(projectId, page, size, Set.of());
  }

  private Mono<PagerResult<DeviceInfo>> page(String projectId, int page, int size, Set<String> ids) {
    return scope.current().flatMap(c -> {
      var dimension = DimensionAssetsTerm.of(
        // 配置目录先读取当前应用授权设备，不能要求尚未配置的设备已绑定业务项目组织。
        List.of(projectId == null
          ? DimensionInfo.of("business_application", c.applicationId())
          : DimensionInfo.of("org", projectId)),
        "device"
      );
      // 只有组织按下级闭包展开；应用设备是直接绑定，没有组织闭包记录。
      dimension.setClosure(projectId != null);
      var query = QueryParamEntity.newQuery()
        .accept("id", DimensionAssetsTermBuilder.termType, dimension);
      if (!ids.isEmpty()) query.in("id", ids);
      // Cloud's AssetsQueryCommandHandler owns device permission injection. Local
      // tenant/organization tables and query contexts belong to the facility service.
      return support().flatMap(s -> {
        var command = QueryPagerCommand.of(DeviceInfo.class);
        command.withQueryParam(query.orderByAsc("id").doPaging(page, size).getParam());
        return s.execute(command);
      });
    });
  }

  Mono<Set<String>> boundDeviceIds(Set<String> ids) {
    if (ids.isEmpty()) return Mono.just(Set.of());
    return page(null, 0, ids.size(), ids)
      .switchIfEmpty(Mono.error(new IllegalStateException("Device binding query returned no page")))
      .map(result -> {
        var bound = result.getData().stream().map(DeviceInfo::getId)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
        FacilityScope.require(result.getTotal() == bound.size() && ids.containsAll(bound), "source_incomplete");
        return bound;
      });
  }

  public Mono<List<DeviceInfo>> snapshot(String projectId) {
    return page(projectId, 0, 100).flatMap(first -> {
      FacilityScope.require(first.getTotal() <= 20000, "too_many");
      int pages = (int) ((first.getTotal() + 99) / 100);
      return Flux.range(1, Math.max(0, pages - 1))
        .concatMap(index ->
          page(projectId, index, 100).doOnNext(p ->
            FacilityScope.require(
              p.getTotal() == first.getTotal(),
              "source_changed"
            )
          )
        )
        .startWith(first)
        .flatMapIterable(PagerResult::getData)
        .collectList()
        .doOnNext(rows -> {
          FacilityScope.require(
            rows.size() == first.getTotal() &&
              rows.stream().map(DeviceInfo::getId).distinct().count() ==
                rows.size(),
            "source_incomplete"
          );
        });
    });
  }

  public Mono<ThingMetadata> metadata(String cloudId) {
    // Cloud owns device AssetsHolder checks; evaluating its asset bindings in this database is invalid.
    return support()
      .flatMap(s -> s.execute(new GetMetadataCommand().withId(cloudId)));
  }

  public Flux<DeviceProperty> history(
    String cloudId,
    String property,
    long from,
    long to
  ) {
    return Flux.defer(() ->
        historyPage(cloudId, property, from, to, 0).flatMapMany(first -> {
          FacilityScope.require(first.getTotal() <= 50000, "too_many");
          int pages = (int) ((first.getTotal() + 199) / 200);
          return Flux.range(1, Math.max(0, pages - 1))
            .concatMap(p ->
              historyPage(cloudId, property, from, to, p).doOnNext(next ->
                FacilityScope.require(
                  next.getTotal() == first.getTotal(),
                  "source_changed"
                )
              )
            )
            .startWith(first)
            .flatMapIterable(PagerResult::getData)
            .collectList()
            .doOnNext(rows ->
              FacilityScope.require(
                rows.size() == first.getTotal(),
                "source_incomplete"
              )
            )
            .flatMapMany(Flux::fromIterable);
        })
    );
  }

  Mono<PagerResult<DeviceProperty>> historyPage(
    String id,
    String property,
    long from,
    long to,
    int page
  ) {
    return CommandServices.getReactive(QueryFacilityPropertyPageCommand.SERVICE_ID)
      .flatMap(s -> s.execute(historyCommand(id, property, from, to, page)))
      .switchIfEmpty(Mono.error(new IllegalStateException("Device history query returned no page")))
      .as(MonoTracer.create("/property/facility-energy/energy/source-page"));
  }

  static QueryFacilityPropertyPageCommand historyCommand(
    String id, String property, long from, long to, int page
  ) {
    var cmd = new QueryFacilityPropertyPageCommand();
    cmd.setDeviceId(id);
    cmd.setProperty(property);
    cmd.setQuery(
      QueryParamEntity.newQuery()
        .gte("timestamp", from)
        .lt("timestamp", to)
        .orderByAsc("timestamp")
        .doPaging(page, 200)
        .getParam()
    );
    return cmd;
  }

  Mono<org.jetlinks.core.command.CommandSupport> support() {
    return CommandServices.getReactive(
      SdkServices.deviceService,
      DeviceCommandSupportTypes.device
    );
  }
}
