package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.QueryCurrentPropertyHousesCommand;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.OwnerEnergyMeter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 本人私有仪表读取边界：收费应用成员无需员工设备维护权限，不能枚举别的空间或共享总表。 */
@Service @RequiredArgsConstructor
public class FacilityOwnerEnergy {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private final FacilityScope scope;
  private final DeviceStore devices;
  private final MappingStore mappings;
  private final FacilityReadings readings;

  public Mono<List<OwnerEnergyMeter>> query(String project, String space, String monthText) {
    return Mono.defer(() -> {
      require(project != null && !project.isBlank() && project.length() <= 64 &&
        space != null && !space.isBlank() && space.length() <= 64 && monthText != null &&
        monthText.matches("[0-9]{4}-(0[1-9]|1[0-2])"), "invalid_period");
      var month = YearMonth.parse(monthText);
      var current = YearMonth.now(ZONE);
      require(!month.isAfter(current) && !month.isBefore(current.minusMonths(11)), "invalid_period");
      var command = new QueryCurrentPropertyHousesCommand();
      command.setProjectId(project); command.setSpaceId(space);
      return scope.current().flatMap(c ->
        AssetsHolder.assertPermission(AssetType.of("organization"), project, CrudAssetPermission.read)
          .then(CommandSupportManagerProviders.getCommandSupport(QueryCurrentPropertyHousesCommand.SERVICE_ID)
            .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.facility.owner_energy_unavailable",503)))
            .flatMap(s -> s.execute(command)))
          .flatMap(houses -> {
            var own = houses.stream().filter(h -> project.equals(h.context().house().projectId()) &&
              space.equals(h.context().house().spaceId()) && !h.context().customers().isEmpty()).findFirst()
              .orElseThrow(org.hswebframework.web.authorization.exception.AccessDenyException::new);
            Long start = own.context().customers().get(0).startTime();
            // 无法证明关系起点时不披露过往用量；后续采集仍能显示。
            long since = start == null ? System.currentTimeMillis() : start;
            return load(c.tenantId(), project, space, month, since);
          }));
    });
  }

  private Mono<List<OwnerEnergyMeter>> load(String tenant, String project, String space, YearMonth month, long since) {
    return devices.createQuery().where("tenantId", tenant).and("projectId", project).and("spaceId", space)
      .and("deleted", false).and("syncState", "active").and("energyEnabled", true).and("projectConfirmed", true)
      .fetch().take(101).collectList().flatMap(rows -> {
        require(rows.size() <= 100, "too_many");
        var unique = new HashSet<String>();
        return Flux.fromIterable(rows).concatMap(device -> {
          // 一个物理设备可被绑定到多个应用；拒绝重复来源，不把同一用量加两遍。
          require(device.getCloudDeviceId() != null && unique.add(device.getCloudDeviceId()), "owner_energy_duplicate");
          return mappings.createQuery().where("tenantId", tenant).and("applicationId", device.getApplicationId())
            .and("deviceId", device.getId()).and("projectId", project).and("deleted", false)
            .and("enabled", true).and("meterLevel", "submeter").fetchOne()
            .filter(m -> Objects.equals(m.getEnergyType(), device.getMeterType()) &&
              Set.of("water", "electricity", "gas").contains(Objects.toString(m.getEnergyType(), "")) && m.getUsagePropertyId() != null)
            .flatMap(mapping -> {
              long from = month.atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
              long to = Math.min(System.currentTimeMillis(), month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
              long visibleFrom = Math.max(since, Objects.requireNonNullElse(device.getAllocationSince(), System.currentTimeMillis()));
              if (visibleFrom >= to) return Mono.just(view(device, mapping, month, from, to, List.of(), visibleFrom));
              // mapping来自上方已核验的本人房屋和同租户设备，而非客户端；查询继续限定其应用和项目。
              return readings.seriesForVisibleMapping(mapping, mapping.getUsagePropertyId(), Math.max(from,visibleFrom), to)
                .map(series -> view(device, mapping, month, from, to, series.stream()
                  .filter(r -> r.getObservedTime() >= visibleFrom).toList(), visibleFrom));
            });
        }).collectList();
      });
  }

  static OwnerEnergyMeter view(DeviceEntity device, MappingEntity mapping, YearMonth month,
    long from, long to, List<ReadingEntity> series, long since) {
    long stale = Objects.requireNonNullElse(mapping.getStaleAfterSeconds(), 3600L);
    var usage = EnergyMath.usage(series, from, to, stale);
    var days = new ArrayList<OwnerEnergyMeter.Day>();
    for (var day = month.atDay(1); !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
      long start = day.atStartOfDay(ZONE).toInstant().toEpochMilli();
      if (start >= to) break;
      long end = Math.min(to, day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
      var daily = EnergyMath.usage(series, start, end, stale);
      days.add(new OwnerEnergyMeter.Day(day.toString(), quantity(daily, series), daily.complete() && since <= start));
    }
    return new OwnerEnergyMeter(device.getId(), device.getName(), mapping.getEnergyType(), quantity(usage, series),
      usage.complete() && since <= from, usage.lastTime(), days);
  }

  private static String quantity(EnergyMath.Usage usage, List<ReadingEntity> series) {
    // 单个累计读数只能充当基线，不足以说明该期间的用量。
    return usage.accepted() == 0 || usage.firstTime().equals(usage.lastTime()) &&
      series.stream().anyMatch(row -> "accepted".equals(row.getStatus()) && "counter".equals(row.getMetricKind()) && usage.firstTime().equals(row.getObservedTime()))
      ? null : usage.value().toPlainString();
  }
}
