package org.jetlinks.pro.property.facility.command;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.command.AbstractConvertCommand;
import org.jetlinks.sdk.server.commons.cmd.QueryPagerCommand;
import org.jetlinks.sdk.server.device.DeviceProperty;
import org.jetlinks.sdk.server.device.cmd.QueryPropertyPageCommand;
import reactor.core.publisher.Mono;

/**
 * Historical property page contract, installed in the Cloud device process and the consumer.
 * Preserves the caller's authentication, tenant and asset context; read-only, with errors propagated.
 * Query parameters are flattened into the wire payload rather than kept in SDK transient fields.
 */
public class QueryFacilityPropertyPageCommand extends AbstractConvertCommand<
  Mono<PagerResult<DeviceProperty>>, QueryFacilityPropertyPageCommand
> {
  public static final String SERVICE_ID = "propertyFacilityCloudService:history";
  public static final String COMMAND_ID = "QueryFacilityPropertyPage";

  public QueryFacilityPropertyPageCommand() {
    withConverter(QueryPagerCommand.of(DeviceProperty.class)::createResponseData);
  }

  @Override
  public String getCommandId() {
    return COMMAND_ID;
  }

  public void setDeviceId(String id) {
    with("deviceId", id);
  }

  public String getDeviceId() {
    return getOrNull("deviceId", String.class);
  }

  public void setProperty(String property) {
    with("property", property);
  }

  public String getProperty() {
    return getOrNull("property", String.class);
  }

  public void setQuery(QueryParamEntity query) {
    with(query);
  }

  /** Execute locally in Cloud: bypass only the defective RPC factory, never permission checks. */
  public QueryPropertyPageCommand toCloudCommand() {
    var command = new QueryPropertyPageCommand() {
      @Override
      public String getCommandId() {
        return "QueryPropertyPage";
      }
    };
    command.with(asMap());
    command.withConverter(QueryPagerCommand.of(DeviceProperty.class)::createResponseData);
    return command;
  }
}
