package org.jetlinks.pro.property.facility.file;

import lombok.RequiredArgsConstructor;
import java.util.Collection;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jetlinks.pro.io.file.FileInfo;
import org.jetlinks.pro.io.file.FileManager;
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Protects marked attachments at the file owner's public SPI, including generic download, thumbnails
 * and compression. Checks happen before metadata/bytes leave the provider, so a known ID/access key
 * cannot bypass the business scope. Other applications' unmarked files are unaffected.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class FacilityCloudFileGuard {
  private final FacilityCloudFileScope scope;

  @Around("execution(reactor.core.publisher.Mono org.jetlinks.pro.io.file.FileManager+.getFile*(..))")
  @SuppressWarnings("unchecked")
  public Object metadata(ProceedingJoinPoint call) throws Throwable {
    return ((Mono<FileInfo>) call.proceed()).flatMap(info -> scope.authorize(info).thenReturn(info));
  }

  @Around("execution(reactor.core.publisher.Flux org.jetlinks.pro.io.file.FileManager+.read(..)) && args(id,..)")
  public Object read(ProceedingJoinPoint call, String id) throws Throwable {
    // Use the unproxied public target to avoid running the same scope check twice.
    var files = (FileManager) call.getTarget();
    var body = (Publisher<?>) call.proceed();
    return files.getFile(id).flatMap(scope::authorize).thenMany(Flux.from(body));
  }

  @Around("execution(reactor.core.publisher.Flux org.jetlinks.pro.io.file.FileManager+.compress(..)) && args(ids,..)")
  public Object compress(ProceedingJoinPoint call, Collection<String> ids) throws Throwable {
    var files = (FileManager) call.getTarget();
    var body = (Publisher<?>) call.proceed();
    // Default interface compression can self-invoke read, bypassing the Spring read advice.
    return Flux.fromIterable(ids).concatMap(id -> files.getFile(id).flatMap(scope::authorize))
      .thenMany(Flux.from(body));
  }
}
