package org.jetlinks.pro.property.facility.service;

import java.util.List;
import java.util.Map;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.*;
import org.jetlinks.pro.command.rule.RuleCommandServices;
import org.jetlinks.pro.command.rule.RelievedAlarmCommand;
import org.jetlinks.pro.command.rule.data.RelieveInfo;
import org.jetlinks.pro.property.facility.model.CloudAlarmRecord;
import org.jetlinks.pro.property.facility.model.CloudAlarmLevels;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** 使用平台已注册的告警命令；保留调用者身份，仅查询已授权设备的来源/目标记录。 */
@Service
public class FacilityCloudAlarms {
  private static org.hswebframework.web.exception.BusinessException unavailable() {
    return new org.hswebframework.web.exception.BusinessException("error.facility.alarm_source_unavailable", 503);
  }

  /** 调用正式解除命令，沿用请求身份；平台从原记录恢复绑定上下文，不借用设备创建人身份。 */
  public Mono<Void> relieve(CloudAlarmRecord source, String reason) {
    return Mono.defer(() -> {
      var info = new RelieveInfo();
      info.setAlarmConfigId(source.getAlarmConfigId()); info.setTargetType(source.getTargetType());
      info.setTargetId(source.getTargetId()); info.setSourceType(source.getSourceType()); info.setSourceId(source.getSourceId());
      info.setAlarmTime(source.getAlarmTime()); info.setAlarmRelieveType("user");
      info.setRelieveReason(reason); info.setDescribe(reason);
      return reliefSupport().flatMap(s -> s.execute(new RelievedAlarmCommand().setRelieveInfo(info)))
        .switchIfEmpty(Mono.error(unavailable())).doOnNext(result -> FacilityScope.require(
          result.isRelieved() && source.getId().equals(result.getRecordId()), "alarm_relief_failed")).then();
    });
  }

  Mono<CommandSupport> reliefSupport() {
    return CommandSupportManagerProviders.getCommandSupport(InternalSdkServices.ruleService, RuleCommandServices.alarm)
      .switchIfEmpty(Mono.error(unavailable()));
  }

  /** 平台现有 QueryAlarmLevel 命令只读默认等级，保留调用上下文，不依赖规则引擎内部类。 */
  public Mono<List<CloudAlarmLevels.Level>> levels() {
    return levelSupport().flatMap(s -> s.executeToMono("QueryAlarmLevel", Map.of()))
      .map(value -> FastBeanCopier.copy(value, new CloudAlarmLevels()))
      .flatMap(value -> Mono.justOrEmpty(value.getLevels()))
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.facility.alarm_source_unavailable", 503)));
  }

  Mono<CommandSupport> levelSupport() {
    return CommandSupportManagerProviders.getCommandSupport(InternalSdkServices.ruleService, RuleCommandServices.alarmConfigService)
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.facility.alarm_source_unavailable", 503)));
  }

  public Mono<List<CloudAlarmRecord>> records(String deviceId) {
    return page(deviceId, 0).flatMap(first -> {
      FacilityScope.require(first.getTotal() <= 20000, "too_many");
      return Flux.range(1, Math.max(0, (int) ((first.getTotal() + 199) / 200) - 1))
        .concatMap(index -> page(deviceId, index).doOnNext(next ->
          FacilityScope.require(next.getTotal() == first.getTotal(), "source_changed")))
        .startWith(first).concatMapIterable(PagerResult::getData).collectList()
        .doOnNext(rows -> FacilityScope.require(rows.size() == first.getTotal()
          && rows.stream().map(CloudAlarmRecord::getId).distinct().count() == rows.size(), "source_incomplete"));
    });
  }

  private Mono<PagerResult<CloudAlarmRecord>> page(String deviceId, int index) {
    return support().flatMap(s -> s.execute(QueryPagerCommand.of(CloudAlarmRecord.class)
      .withQueryParam(query(deviceId, index))))
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.facility.alarm_source_unavailable", 503)));
  }

  static QueryParamEntity query(String deviceId, int page) {
    return QueryParamEntity.newQuery()
      .nest().is("sourceType", "device").and("sourceId", deviceId).end()
      .orNest().is("targetType", "device").and("targetId", deviceId).end()
      .orderByAsc("id").doPaging(page, 200).getParam();
  }

  Mono<CommandSupport> support() {
    return CommandSupportManagerProviders.getCommandSupport(InternalSdkServices.ruleService, RuleCommandServices.alarmRecordService)
      .switchIfEmpty(Mono.error(new org.hswebframework.web.exception.BusinessException("error.facility.alarm_source_unavailable", 503)));
  }
}
