package cn.geoair.map.dynamic.terrain.ttg.png.utils;

import cn.geoair.base.Gir;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/**
 * 文件 IO 与通用工具方法。
 * <p>提供 UUID 生成、线程休眠、耗时格式化、目录创建与清空等基础能力。</p>
 */
public class IoHelper {

    /**
     * 生成无连字符的 UUID 字符串（32位）。
     *
     * @return 例如 "a1b2c3d4e5f67890abcdef1234567890"
     */
    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 休眠指定毫秒，被中断时自动恢复中断标志。
     *
     * @param ms 休眠毫秒数
     */
    public static void waitMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 将毫秒数格式化为人类可读的时间字符串。
     * <p>自动选择 ms / sec / min / hour 单位。</p>
     *
     * @param timeInMs 毫秒数
     * @return 包含数值和单位的结果对象
     */
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

    /**
     * 递归创建目录（等同 mkdir -p）。
     *
     * @param dirName 目录路径
     */
    public static void mkdirsSync(String dirName) {
        File dir = new File(dirName);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * 清空指定目录下的所有文件和子目录，保留目录本身。
     *
     * @param fold 目录路径
     * @throws IOException 删除失败时抛出
     */
    public static void emptyDir(String fold) throws IOException {
        Path path = Paths.get(fold);
        if (!Files.exists(path)) {
            return;
        }
        Gir.log.info("清空目录中...");
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

    /**
     * 可读时间格式化结果。
     */
    public static class PrettyTimeResult {
        /** 数值 */
        public final double resultTime;
        /** 单位：ms / sec / min / hour */
        public final String unit;

        public PrettyTimeResult(double resultTime, String unit) {
            this.resultTime = resultTime;
            this.unit = unit;
        }
    }
}
