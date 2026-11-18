package org.jetlinks.pro.property.facility.command;

import java.util.List;
import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.facility.model.OwnerEnergyMeter;
import reactor.core.publisher.Mono;

/**
 * 本人房屋的私有分表用量；提供方重新核验本人关联、租户和项目资产，不接受客户或设施应用ID。
 * 仅返回已采集用量，不作为计费证据，未知数据为null。
 * @since 2.20.0
 * @see OwnerEnergyMeter
 */
public class QueryOwnerFacilityEnergyCommand extends AbstractCommand<Mono<List<OwnerEnergyMeter>>, QueryOwnerFacilityEnergyCommand> {
  public static final String SERVICE_ID = "facilityService:owner-energy";
  public String getProjectId() { return getOrNull("projectId", String.class); }
  public void setProjectId(String value) { with("projectId", value); }
  public String getSpaceId() { return getOrNull("spaceId", String.class); }
  public void setSpaceId(String value) { with("spaceId", value); }
  public String getMonth() { return getOrNull("month", String.class); }
  public void setMonth(String value) { with("month", value); }
}
