package org.jetlinks.pro.property.facility.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.metadata.*;
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

/** Cloud公开SDK适配器；组织闭包、调用人device资产及当前租户取交集，不返回连接配置或密钥。 */
@Service
@RequiredArgsConstructor
public class FacilityCloud {

  private final FacilityScope scope;

  public Mono<PagerResult<DeviceInfo>> page(
    String projectId,
    int page,
    int size
  ) {
    return scope.current().flatMap(c -> {
      var dimension = DimensionAssetsTerm.of(
        List.of(DimensionInfo.of("org", projectId)),
        "device"
      );
      dimension.setClosure(true);
      var query = QueryParamEntity.newQuery()
        .accept("id", DimensionAssetsTermBuilder.termType, dimension)
        .orderByAsc("id")
        .doPaging(page, size)
        .getParam();
      // Cloud's AssetsQueryCommandHandler owns device permission injection. Local
      // tenant/organization tables and query contexts belong to the facility service.
      return support().flatMap(s -> {
        var command = QueryPagerCommand.of(DeviceInfo.class);
        command.withQueryParam(query);
        return s.execute(command);
      });
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
      .flatMap(s -> s.execute(historyCommand(id, property, from, to, page)));
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

  public Flux<DevicePropertyProperties> subscribe(
    List<String> ids,
    List<String> properties
  ) {
    // The public subscription command applies the same Cloud-owned asset permissions.
    return support().flatMapMany(s ->
      s.execute(
        new SubscribeDevicePropertiesCommand()
          .setDeviceIds(ids)
          .setPropertyIds(properties)
      )
    );
  }

  Mono<org.jetlinks.core.command.CommandSupport> support() {
    return CommandServices.getReactive(
      SdkServices.deviceService,
      DeviceCommandSupportTypes.device
    );
  }
}
