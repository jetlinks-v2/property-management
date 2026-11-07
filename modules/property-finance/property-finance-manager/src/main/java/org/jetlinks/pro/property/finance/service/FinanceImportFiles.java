package org.jetlinks.pro.property.finance.service;

import com.alibaba.fastjson.JSON;

import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Function;

/** 阶段文件只存于服务端持久卷；每次最多驻留一行和一个100行数据库批次。 */
final class FinanceImportFiles {
    private FinanceImportFiles() {}

    static <T> Flux<T> read(Path path, Class<T> type) {
        return Flux.using(
                        () -> Files.newBufferedReader(path, StandardCharsets.UTF_8),
                        reader ->
                                Flux.<T>generate(
                                        sink -> {
                                            try {
                                                var line = reader.readLine();
                                                if (line == null) sink.complete();
                                                else sink.next(JSON.parseObject(line, type));
                                            } catch (Exception e) {
                                                sink.error(e);
                                            }
                                        }),
                        reader -> {
                            try {
                                reader.close();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    static <T> Mono<Integer> write(
            Path path, Flux<T> rows, Function<Integer, Mono<Void>> progress) {
        return Mono.fromCallable(() -> Files.exists(path))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        ready -> {
                            if (ready)
                                return read(path, com.alibaba.fastjson.JSONObject.class)
                                        .count()
                                        .map(Math::toIntExact);
                            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
                            return writeNew(temporary, rows, progress)
                                    .flatMap(
                                            count ->
                                                    Mono.fromCallable(
                                                                    () -> {
                                                                        Files.move(
                                                                                temporary,
                                                                                path,
                                                                                StandardCopyOption
                                                                                        .ATOMIC_MOVE,
                                                                                StandardCopyOption
                                                                                        .REPLACE_EXISTING);
                                                                        return count;
                                                                    })
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic()));
                        });
    }

    private static <T> Mono<Integer> writeNew(
            Path path, Flux<T> rows, Function<Integer, Mono<Void>> progress) {
        return Mono.using(
                        () -> Files.newBufferedWriter(path, StandardCharsets.UTF_8),
                        writer -> {
                            var count = new int[1];
                            return rows.concatMap(
                                            row ->
                                                    Mono.fromCallable(
                                                                    () -> {
                                                                        writer.write(
                                                                                JSON.toJSONString(
                                                                                        row));
                                                                        writer.newLine();
                                                                        return ++count[0];
                                                                    })
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic())
                                                            .flatMap(
                                                                    n ->
                                                                            n % 10 == 0
                                                                                    ? progress.apply(
                                                                                                    n)
                                                                                            .thenReturn(
                                                                                                    n)
                                                                                    : Mono.just(n)),
                                            1)
                                    .last(0)
                                    .flatMap(
                                            n ->
                                                    Mono.fromRunnable(
                                                                    () -> {
                                                                        try {
                                                                            writer.flush();
                                                                        } catch (IOException e) {
                                                                            throw new UncheckedIOException(
                                                                                    e);
                                                                        }
                                                                    })
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic())
                                                            .then(progress.apply(n))
                                                            .thenReturn(n))
                                    .publishOn(Schedulers.boundedElastic());
                        },
                        writer -> {
                            try {
                                writer.close();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
