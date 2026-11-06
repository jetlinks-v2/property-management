package org.jetlinks.pro.property.facility.service;

import java.util.Collection;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.facility.command.AssertFacilityFilesCommand;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 由文件所有者校验真实操作人的附件权限；远程失败时禁止关联附件。 */
@Component
public class FacilityFiles {

  public Mono<Void> validate(Collection<String> ids, String projectId) {
    if (ids == null || ids.isEmpty()) return Mono.empty();
    var command = new AssertFacilityFilesCommand();
    command.setProjectId(projectId);
    command.setFileIds(ids.stream().distinct().toList());
    return CommandSupportManagerProviders.getCommandSupport(
      AssertFacilityFilesCommand.SERVICE_ID
    )
      .switchIfEmpty(
        Mono.error(
          new BusinessException("error.facility.file_service_unavailable", 503)
        )
      )
      .flatMap(support -> support.execute(command))
      .filter(Boolean.TRUE::equals)
      .switchIfEmpty(
        Mono.error(
          new BusinessException("error.facility.attachment_missing", 400)
        )
      )
      .then();
  }
}
