package org.jetlinks.pro.property.facility.file;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.io.file.FileProperties;
import org.jetlinks.pro.io.utils.FileUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Facility upload entry in the Cloud file-owner process (/file routing), sharing platform storage
 * and suffix permission checks. Only minimal file identity is returned, never storage/access keys.
 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/file/facility")
public class FacilityCloudFileController {
  private final FileManager files;
  private final FileProperties properties;
  private final FacilityCloudFileScope scope;

  public record Uploaded(String id, String name, long length) {}

  @PostMapping("/upload")
  public Mono<Uploaded> upload(@RequestParam String projectId, @RequestPart("file") Mono<FilePart> part) {
    return scope.current(projectId).flatMap(authorized -> Authentication.currentReactive()
      .zipWith(part).flatMap(data -> {
        properties.validateUploadPermission(FileUtils.getExtension(data.getT2().filename()), data.getT1());
        return files.saveFile(data.getT2())
          .flatMap(info -> FacilityCloudFileScope.stored(info).filter(authorized::equals).isPresent()
            ? Mono.just(info)
            : files.delete(info.getId()).then(Mono.error(new AccessDenyException())))
          .contextWrite(c -> c.put(FacilityCloudFileScope.Scope.class, authorized));
      }).map(info -> new Uploaded(info.getId(), info.getName(), info.getLength())));
  }
}
