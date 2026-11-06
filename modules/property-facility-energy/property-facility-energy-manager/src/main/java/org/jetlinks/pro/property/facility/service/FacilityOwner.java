package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.property.facility.entity.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 业主专用独立授权边界：本人绑定及时间区间，不要求或授予通用Cloud设备读取权。 */
@Service
@RequiredArgsConstructor
public class FacilityOwner {

  private final FacilityScope scope;
  private final BindingStore bindings;
  private final DeviceStore devices;
  private final MappingStore mappings;
  private final ReadingStore readings;
  private final NotificationStore notifications;

  public Flux<Map<String, Object>> meters() {
    return scope.current().flatMapMany(c ->
      bindings
        .createQuery()
        .where("tenantId", c.tenantId())
        .and("applicationId", c.applicationId())
        .and("userId", c.userId())
        .and("deleted", false)
        .orderBy(
          org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.desc(
            "startTime"
          )
        )
        .fetch()
        .take(201)
        .collectList()
        .doOnNext(rows -> require(rows.size() <= 200, "too_many"))
        .flatMapMany(Flux::fromIterable)
        .concatMap(binding ->
          devices
            .findById(binding.getDeviceId())
            .filter(
              d ->
                d.getTenantId().equals(c.tenantId()) &&
                d.getApplicationId().equals(c.applicationId())
            )
            .map(device -> {
              Map<String, Object> out = new LinkedHashMap<>();
              out.put("bindingId", binding.getId());
              out.put("deviceId", device.getId());
              out.put(
                "name",
                Objects.toString(device.getName(), device.getSourceName())
              );
              out.put("spaceId", binding.getSpaceId());
              out.put("startTime", binding.getStartTime());
              out.put("endTime", binding.getEndTime());
              out.put("current", binding.getEndTime() == null);
              return out;
            })
        )
    );
  }

  public Mono<BindingEntity> binding(String id) {
    return scope.current().flatMap(c ->
      bindings
        .findById(id)
        .filter(
          b ->
            c.tenantId().equals(b.getTenantId()) &&
            c.applicationId().equals(b.getApplicationId()) &&
            c.userId().equals(b.getUserId()) &&
            !Boolean.TRUE.equals(b.getDeleted())
        )
        .switchIfEmpty(Mono.error(new AccessDenyException()))
    );
  }

  public Mono<Map<String, Object>> detail(
    String bindingId,
    long from,
    long to
  ) {
    require(from < to && to - from <= 366L * 86400000, "invalid_period");
    return binding(bindingId).flatMap(binding -> {
      long start = Math.max(from, binding.getStartTime()),
        end = Math.min(
          to,
          binding.getEndTime() == null
            ? System.currentTimeMillis() + 1
            : binding.getEndTime()
        );
      require(start < end, "binding_period");
      return mappings
        .createQuery()
        .where("tenantId", binding.getTenantId())
        .and("applicationId", binding.getApplicationId())
        .and("deviceId", binding.getDeviceId())
        .and("projectId", binding.getProjectId())
        .and("deleted", false)
        .fetchOne()
        .flatMap(mapping ->
          Flux.fromIterable(mapping.getMetrics())
            .concatMap(metric ->
              readings
                .createQuery()
                .where("tenantId", binding.getTenantId())
                .and("applicationId", binding.getApplicationId())
                .and("deviceId", binding.getDeviceId())
                .and("projectId", binding.getProjectId())
                .and("propertyId", metric.getPropertyId())
                .and("status", "accepted")
                .gte("observedTime", start)
                .lt("observedTime", end)
                .orderBy(
                  org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc(
                    "observedTime"
                  )
                )
                .fetch()
                .take(10001)
                .collectList()
                .doOnNext(rows -> require(rows.size() <= 10000, "too_many"))
                .map(rows -> {
                  Map<String, Object> metricView = new LinkedHashMap<>();
                  metricView.put("propertyId", metric.getPropertyId());
                  metricView.put("name", metric.getName());
                  metricView.put("kind", metric.getKind());
                  metricView.put("unit", metric.getUnit());
                  var latest = rows.isEmpty()
                    ? null
                    : rows.get(rows.size() - 1);
                  metricView.put(
                    "stale",
                    latest == null ||
                      System.currentTimeMillis() - latest.getObservedTime() >
                        mapping.getStaleAfterSeconds() * 1000
                  );
                  metricView.put(
                    "readings",
                    rows
                      .stream()
                      .map(row -> {
                        var v = new LinkedHashMap<String, Object>();
                        v.put("time", row.getObservedTime());
                        v.put(
                          "value",
                          row.getValue() == null
                            ? row.getTextValue()
                            : row.getValue()
                        );
                        return v;
                      })
                      .toList()
                  );
                  if (
                    Set.of("counter", "increment").contains(metric.getKind())
                  ) metricView.put(
                    "usage",
                    EnergyMath.usage(
                      rows,
                      start,
                      end,
                      mapping.getStaleAfterSeconds()
                    )
                  );
                  return metricView;
                })
            )
            .collectList()
            .map(metrics -> {
              Map<String, Object> out = new LinkedHashMap<>();
              out.put("bindingId", bindingId);
              out.put("energyType", mapping.getEnergyType());
              out.put("from", start);
              out.put("to", end);
              out.put("metrics", metrics);
              return out;
            })
        );
    });
  }

  public Flux<Map<String, Object>> messages() {
    return scope.current().flatMapMany(c ->
      notifications
        .createQuery()
        .where("tenantId", c.tenantId())
        .and("applicationId", c.applicationId())
        .and("userId", c.userId())
        .and("channel", "h5")
        .and("state", "generated")
        .orderBy(
          org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.desc(
            "createTime"
          )
        )
        .fetch()
        .take(200)
        .map(row -> {
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("id", row.getId());
          out.put("title", row.getTitle());
          out.put("message", row.getMessage());
          out.put("createTime", row.getCreateTime());
          out.put("readTime", row.getReadTime());
          return out;
        })
    );
  }

  public Mono<Void> markRead(String id) {
    return scope
      .current()
      .flatMap(c ->
        notifications
          .createUpdate()
          .set("readTime", System.currentTimeMillis())
          .where("id", id)
          .and("tenantId", c.tenantId())
          .and("applicationId", c.applicationId())
          .and("userId", c.userId())
          .and("channel", "h5")
          .execute()
      )
      .then();
  }
}
