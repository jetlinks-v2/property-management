package org.jetlinks.pro.property.facility.web;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.facility.command.QueryOwnerFacilityEnergyCommand;
import org.jetlinks.pro.property.facility.model.OwnerEnergyMeter;
import org.jetlinks.pro.property.facility.service.FacilityOwnerEnergy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 本人读取契约独立于员工菜单权限；服务内部执行房屋及项目授权。 @since 2.20.0 */
@Component @Authorize @RequiredArgsConstructor
@CommandService(id = QueryOwnerFacilityEnergyCommand.SERVICE_ID, name = "本人房屋能源用量")
public class FacilityOwnerEnergySupport {
  private final FacilityOwnerEnergy energy;
  @CommandHandler
  public Mono<List<OwnerEnergyMeter>> queryOwnerEnergy(QueryOwnerFacilityEnergyCommand command) {
    return energy.query(command.getProjectId(), command.getSpaceId(), command.getMonth());
  }
}
