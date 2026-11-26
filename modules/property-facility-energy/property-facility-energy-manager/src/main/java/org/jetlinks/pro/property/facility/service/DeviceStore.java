package org.jetlinks.pro.property.facility.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.facility.entity.DeviceEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Device响应式持久化；独立入口的权限和事务由领域服务拥有。 */
@Service
public class DeviceStore
  extends GenericReactiveCrudService<DeviceEntity, String> {

  @Override
  public ReactiveUpdate<DeviceEntity> createUpdate() {
    // Device edits can carry a stale cursor snapshot and must not overwrite collection progress.
    return super.createUpdate().excludes("saasAlarmSyncedThrough");
  }

  Mono<Integer> advanceAlarmCursor(DeviceEntity device, long until) {
    var update = super.createUpdate().set("saasAlarmSyncedThrough", until)
      .where("id", device.getId()).and("version", device.getVersion())
      .and("deleted", false).and("syncState", "active");
    if (device.getSaasAlarmSyncedThrough() == null) {
      update.isNull("saasAlarmSyncedThrough");
    } else {
      update.and("saasAlarmSyncedThrough", device.getSaasAlarmSyncedThrough());
    }
    return update.execute();
  }
}
