package org.jetlinks.pro.property.facility.web;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.*;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.property.facility.command.QueryFacilityMeterUsageCommand;
import org.jetlinks.pro.property.facility.model.MeterUsageEvidence;
import org.jetlinks.pro.property.facility.service.FacilityMeterCharges;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Finance reads evidence through the current caller; this is not a privileged device-data export. */
@Component @RequiredArgsConstructor @Authorize
@CommandService(id = QueryFacilityMeterUsageCommand.SERVICE_ID, name = "表计用量")
@Resource(id = "facility-meter-billing", name = "水电计费配置")
public class FacilityMeterUsageSupport {
  private final FacilityMeterCharges charges;
  @CommandHandler @QueryAction
  public Mono<MeterUsageEvidence> queryFacilityMeterUsage(QueryFacilityMeterUsageCommand command) {
    return command.isIdentityOnly() ? charges.identity(command.getDeviceId(),command.getMonth()) : charges.evidence(command.getDeviceId(), command.getMonth());
  }
}
