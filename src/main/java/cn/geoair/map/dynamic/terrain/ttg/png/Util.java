package cn.geoair.map.dynamic.terrain.ttg.png;

import cn.geoair.base.Gir;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

public class Util {

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void waitMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static PrettyTimeResult prettyTime(long timeInMs) {
        double result;
        String unit;
        if (timeInMs < 1000) {
            result = timeInMs;
            unit = "ms";
        } else if (timeInMs < 60 * 1000) {
            result = timeInMs / 1000.0;
            unit = "sec";
        } else if (timeInMs < 60 * 60 * 1000) {
            result = timeInMs / (60.0 * 1000);
            unit = "min";
        } else {
            result = timeInMs / (60.0 * 60 * 1000);
            unit = "hour";
        }
        return new PrettyTimeResult(result, unit);
    }

    public static void mkdirsSync(String dirName) {
        File dir = new File(dirName);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static void emptyDir(String fold) throws IOException {
        Path path = Paths.get(fold);
        if (!Files.exists(path)) {
            return;
        }
        Gir.log.info("清空目录中。。。。。。。。");
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        Gir.log.info("清空目录完成");
    }

    public static class PrettyTimeResult {
        public final double resultTime;
        public final String unit;

        public PrettyTimeResult(double resultTime, String unit) {
            this.resultTime = resultTime;
            this.unit = unit;
        }
    }
}
