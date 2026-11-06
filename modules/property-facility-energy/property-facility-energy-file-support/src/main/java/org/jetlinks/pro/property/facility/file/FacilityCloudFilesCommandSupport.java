package org.jetlinks.pro.property.facility.file;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.property.facility.command.AssertFacilityFilesCommand;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Validates protected facility attachment references in the original caller context; no public-file fallback. */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = AssertFacilityFilesCommand.SERVICE_ID, name = "设施附件校验")
public class FacilityCloudFilesCommandSupport {
  private final FileManager files;
  private final FacilityCloudFileScope scope;

  @CommandHandler(name = "校验设施附件项目")
  public Mono<Boolean> validate(AssertFacilityFilesCommand command) {
    if (command.getFileIds().size() > 1000) return Mono.error(new IllegalArgumentException("fileIds exceeds 1000"));
    return Flux.fromIterable(command.getFileIds()).distinct().concatMap(id ->
      files.getFile(id).switchIfEmpty(Mono.error(new NotFoundException()))
        .flatMap(info -> scope.requireProject(info, command.getProjectId()))).then(Mono.just(true));
  }
}
