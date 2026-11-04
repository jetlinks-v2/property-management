package org.jetlinks.pro.smartpark.handover.export;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Map;

import javax.imageio.ImageIO;

/** Separate constrained JVM proves generation capacity without conflating POI's reader heap. */
public final class HandoverExportCapacityProbe {
    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(args[1]);
        try (var book = new HandoverExportWorkbook("problem")) {
            Path image = book.directory.resolve("test.png");
            ImageIO.write(
                    new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
            book.row("source", Map.of("description", "真实容量验证"));
            for (int i = 0; i < count; i++)
                book.picture(image, i % 2 == 0 ? "problem" : "rectification");
            book.finish();
            Files.copy(book.workbook, Path.of(args[0]), StandardCopyOption.REPLACE_EXISTING);
            System.out.println(
                    "images="
                            + book.imageCount()
                            + ",rows="
                            + book.rowCount()
                            + ",heapLimit="
                            + Runtime.getRuntime().maxMemory());
        }
    }
}
