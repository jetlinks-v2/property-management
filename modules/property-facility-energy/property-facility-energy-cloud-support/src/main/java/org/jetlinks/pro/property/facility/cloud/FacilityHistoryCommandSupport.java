package org.jetlinks.pro.property.facility.cloud;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.property.facility.command.QueryFacilityPropertyPageCommand;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.device.DeviceCommandSupportTypes;
import org.jetlinks.sdk.server.device.DeviceProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Install only beside Cloud's device provider. Delegates to its public local command with the
 * original Reactor authorization context; does not own device data, synchronize or access tables.
 */
@Component
@Authorize
@CommandService(id = QueryFacilityPropertyPageCommand.SERVICE_ID, name = "设施能源历史属性")
public class FacilityHistoryCommandSupport {
  @CommandHandler(id = QueryFacilityPropertyPageCommand.COMMAND_ID, name = "查询历史属性页")
  public Mono<PagerResult<DeviceProperty>> query(QueryFacilityPropertyPageCommand command) {
    return support().flatMap(local -> local.execute(command.toCloudCommand()));
  }

  Mono<CommandSupport> support() {
    return CommandServices.getReactive(SdkServices.deviceService, DeviceCommandSupportTypes.device);
  }
}
