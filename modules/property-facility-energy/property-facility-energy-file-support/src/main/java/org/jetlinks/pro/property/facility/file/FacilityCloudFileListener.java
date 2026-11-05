package org.jetlinks.pro.property.facility.file;

import org.jetlinks.pro.io.file.FileInfo;
import org.jetlinks.pro.io.file.FileOption;
import org.jetlinks.pro.io.file.listener.FileManagerListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Stamps only authorized facility uploads before file metadata is persisted by the public FileManager SPI. */
@Component
public class FacilityCloudFileListener implements FileManagerListener {
  @Override
  public Mono<Void> beforeUpload(FileInfo info) {
    return Mono.deferContextual(context -> {
      context.<FacilityCloudFileScope.Scope>getOrEmpty(FacilityCloudFileScope.Scope.class).ifPresent(scope -> {
        info.withOther(FacilityCloudFileScope.KEY, scope.metadata());
        info.setOptions(new FileOption[0]);
      });
      return Mono.empty();
    });
  }
}
