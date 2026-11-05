package org.jetlinks.pro.property.facility.cloud;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.property.facility.command.QueryFacilityPropertyPageCommand;
import org.jetlinks.sdk.server.device.DeviceProperty;
import org.jetlinks.sdk.server.device.cmd.QueryPropertyPageCommand;
import org.jetlinks.supports.command.JavaBeanCommandSupport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Reproduces RPC command creation and local SDK delegation, including context and wire fields. */
class FacilityHistoryCommandSupportTest {
  @Test
  void remoteFactoryAndLocalHandlerPreserveIdentityPayloadAndContext() {
    var local = JavaBeanCommandSupport.create(new DeviceProvider());
    var provider = new FacilityHistoryCommandSupport() {
      @Override
      Mono<org.jetlinks.core.command.CommandSupport> support() {
        return Mono.just(local);
      }
    };
    var remote = JavaBeanCommandSupport.create(provider);
    var command = new QueryFacilityPropertyPageCommand();
    command.setDeviceId("meter");
    command.setProperty("total");
    command.setQuery(QueryParamEntity.newQuery().gte("timestamp", 10).lt("timestamp", 100)
      .orderByAsc("timestamp").doPaging(2, 200).getParam());
    assertEquals(command.getCommandId(), remote.createCommand(command.getCommandId()).getCommandId());
    var result = remote.executeToMono(command.getCommandId(), command.asMap())
      .contextWrite(context -> context.put("authorized", true)).block();
    assertInstanceOf(PagerResult.class, result);
    assertEquals(1, ((PagerResult<?>) result).getTotal());
  }

  @Test
  void providerPermissionErrorsPropagateWithoutFallback() {
    var local = JavaBeanCommandSupport.create(new DeviceProvider());
    var provider = new FacilityHistoryCommandSupport() {
      @Override
      Mono<org.jetlinks.core.command.CommandSupport> support() {
        return Mono.just(local);
      }
    };
    var command = new QueryFacilityPropertyPageCommand();
    assertThrows(SecurityException.class, () -> provider.query(command).block());
  }

  public static class DeviceProvider {
    // Like Cloud 2.20, metadata ID differs from the parameter subclass's derived ID.
    public static class LegacyHistoryCommand extends QueryPropertyPageCommand {}

    @CommandHandler(id = "QueryPropertyPage")
    public Mono<PagerResult<DeviceProperty>> query(LegacyHistoryCommand command) {
      return Mono.deferContextual(context -> {
        if (!context.getOrDefault("authorized", false)) return Mono.error(new SecurityException());
        assertEquals("meter", command.getDeviceId());
        assertEquals("total", command.getProperty());
        assertEquals(2, command.asQueryParam().getPageIndex());
        assertEquals(200, command.asQueryParam().getPageSize());
        assertEquals(2, command.asQueryParam().getTerms().size());
        assertEquals("timestamp", command.asQueryParam().getSorts().get(0).getName());
        return Mono.just(new PagerResult<>(1, List.of(new DeviceProperty())));
      });
    }
  }
}
