package cn.geoair.map.dynamic.terrain.ttg.png;

import cn.geoair.base.Gir;
import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.gdal;
import org.gdal.osr.SpatialReference;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class PngTerrainTileGenerator {

    static {
        gdal.AllRegister();
    }

    private static Dataset sourceDs = null;
    private static Dataset projectDs = null;
    private static String projectPath = null;  // 重投影文件路径
    private static String encodePath = null;
    private static TileMath.BBox tileBoundTool;

    private static final int TILE_SIZE = 256;
    private static final int BUFFER = 1;

    private static final AtomicLong tileCount = new AtomicLong(0);
    private static final AtomicLong completeCount = new AtomicLong(0);
    private static final Map<Integer, LevelInfo> levelInfo = new ConcurrentHashMap<>();
    private static ExecutorService executorService;
    private static final Set<Long> childPids = ConcurrentHashMap.newKeySet();

    public static class LevelInfo {
        public int tminx, tminy, tmaxx, tmaxy;
    }

    static class OverviewInfo {
        int index;
        double startX, startY;
        int width, height;
        double resX, resY;
    }

    static class DsInfo {
        int width, height;
        double resX, resY;
        double startX, startY;
        double endX, endY;
        String path;
    }

    static class OverViewInfoResult {
        int maxOverViewsZ, minOverViewsZ;
    }

    /**
     * 地形瓦片生成配置选项类
     * 用于配置地形瓦片生成过程中的所有参数
     */
    public static class Options {
        /**
         * 最小缩放级别（瓦片金字塔最顶层）
         * 例如：0 表示全球范围，1:2，2:4 以此类推
         * 范围通常为 0-20，值越小瓦片越少
         */
        public int minZoom;

        /**
         * 最大缩放级别（瓦片金字塔最底层）
         * 例如：10 表示最精细的级别
         * 范围通常为 0-20，值越大瓦片越精细，数量也越多
         */
        public int maxZoom;

        /**
         * 目标坐标系 EPSG 编码
         * 例如：4326 (WGS84 经纬度), 3857 (Web Mercator), 4490 (CGCS2000)
         * 如果源数据坐标系与此不同，会自动进行重投影
         */
        public int epsg;

        /**
         * 是否清空输出目录
         * 1 = 清空输出目录（删除已有瓦片文件），0 = 保留并覆盖已有文件
         * 建议设置为 1 以避免新旧文件混用导致的问题
         */
        public int isClean;

        /**
         * 重采样方法
         * 用于重投影和构建金字塔时的像素插值算法
         *
         * 可选值及对应含义：
         * 0 = nearest (最近邻) - 速度最快，质量最差
         * 1 = bilinear (双线性) - 平衡速度和质量的常用选择
         * 2 = cubic (三次卷积) - 质量较好，速度较慢
         * 3 = cubicspline (三次样条) - 质量更好，速度更慢
         * 4 = lanczos (Lanczos) - 质量最好，速度最慢，适合细节保留
         * 5 = average (平均值)
         * 6 = mode (众数)
         * 7 = max (最大值)
         * 8 = min (最小值)
         * 9 = med (中值)
         * 10 = q1 (第一四分位数)
         * 11 = q3 (第三四分位数)
         *
         * 建议：地形数据推荐使用 bilinear(1) 或 cubic(2) 以获得平滑效果
         */
        public int resampling;

        /**
         * 编码方式（地形数据 RGB 编码算法）
         *
         * "mapbox" - Mapbox 编码方式
         *   将高程值编码为 RGB 三个通道，每个通道 8 位
         *   优点：兼容 Mapbox 地形服务规范
         *
         * "terrarium" - Terrarium 编码方式
         *   另一种地形编码方式，使用 RGB 编码
         *   优点：在某些 GIS 工具中兼容性更好
         *
         * 仅支持 "mapbox" 或 "terrarium"。
         */
        public String encoding;

        /**
         * 重投影输出文件名（不含扩展名）
         *
         * 用途：当源数据坐标系与目标坐标系(epsg)不一致时，
         * 会先进行重投影，重投影后的文件将保存为此名称
         *
         * 特殊值：
         * "UUID" 或不指定时 - 自动生成 UUID 作为文件名
         * 其他值 - 使用指定的文件名
         *
         * 示例：
         * - "新疆地形_3857" → 生成 "新疆地形_3857.tif"
         * - "UUID" → 生成 "a1b2c3d4-e5f6-7890-abcd-ef1234567890.tif"
         *
         * 注意：重投影文件会保存在输出目录的父目录中，
         * 与瓦片输出目录平级，便于重复使用
         */
        public String reProjectFileName;

        /**
         * 构造地形瓦片生成配置选项
         *
         * @param minZoom 最小缩放级别，通常为 0
         * @param maxZoom 最大缩放级别，建议不超过 15（根据数据量调整）
         * @param epsg 目标坐标系 EPSG 编码，常用值：4326, 3857, 4490
         * @param encoding 编码方式："mapbox" 或 "terrarium"
         * @param isClean 是否清空输出目录：1=清空，0=保留
         * @param resampling 重采样方法：0-11 对应不同算法
         * @param reProjectFileName 重投影文件名，使用 "UUID" 表示自动生成
         */
        public Options(int minZoom, int maxZoom, int epsg, String encoding, int isClean, int resampling, String reProjectFileName) {
            this.minZoom = minZoom;
            this.maxZoom = maxZoom;
            this.epsg = epsg;
            this.encoding = encoding;
            this.isClean = isClean;
            this.resampling = resampling;
            this.reProjectFileName = reProjectFileName;
        }

    }



    public static void main(String input, String output, Options options) throws Exception {
        long startTime = System.currentTimeMillis();

        int minZoom = options.minZoom;
        int maxZoom = options.maxZoom;
        int epsg = options.epsg;
        String encoding = options.encoding;
        int isClean = options.isClean;
        int resampling = options.resampling;
        String reProjectFileName = options.reProjectFileName;

        if (!"mapbox".equals(encoding) && !"terrarium".equals(encoding)) {
            throw new IllegalArgumentException("PNG 地形仅支持 mapbox 或 terrarium 编码: " + encoding);
        }

        boolean isSaveMbtiles = output.endsWith(".mbtiles");
        String outputDir = output;
        if (isSaveMbtiles) {
            outputDir = System.getProperty("java.io.tmpdir") + File.separator + IoHelper.uuid();
        }

        // 获取 output 的父目录，用于存放重投影文件和金字塔文件
        String outputParentDir = new File(output).getParent();
        if (outputParentDir == null) {
            outputParentDir = ".";
        }

        // ============ 获取重投影后数据的实际坐标系 ============
        int actualEpsg = epsg; // 默认使用传入的epsg
        if (projectDs != null) {
            SpatialReference destSrs = projectDs.GetProjectionRef() != null ?
                    new SpatialReference(projectDs.GetProjectionRef()) : null;
            if (destSrs != null) {
                try {
                    actualEpsg = Integer.parseInt(destSrs.GetAuthorityCode(null));
                  Gir.log.info("重投影后数据实际坐标系: EPSG:" + actualEpsg);
                } catch (Exception ignored) {
                  Gir.log.info("无法解析重投影后的坐标系，使用传入的EPSG:" + epsg);
                }
            } else {
              Gir.log.info("重投影后数据没有坐标系信息，使用传入的EPSG:" + epsg);
            }
        }

        int stepIndex = 0;
        if (isClean == 1) {
            if (isSaveMbtiles && new File(output).exists()) {
                new File(output).delete();
            } else {
                IoHelper.emptyDir(output);
            }
            // 清理重投影文件
            if (reProjectFileName != null && !reProjectFileName.isEmpty() && !"UUID".equals(reProjectFileName)) {
                String reprojectPath = outputParentDir + File.separator + reProjectFileName + ".tif";
                File reprojectFile = new File(reprojectPath);
                if (reprojectFile.exists()) {
//                    reprojectFile.delete();
//                    Gir.log.info(">> 步骤" + (++stepIndex) + ": 清理旧重投影文件 - " + reprojectPath);
                }
                // 清理金字塔文件
                String ovrPath = reprojectPath + ".ovr";
                File ovrFile = new File(ovrPath);
                if (ovrFile.exists()) {
//                    ovrFile.delete();
                }
                String auxPath = reprojectPath + ".aux.xml";
                File auxFile = new File(auxPath);
                if (auxFile.exists()) {
//                    auxFile.delete();
                }
            }
          Gir.log.info(">> 步骤" + (++stepIndex) + ": 清空输出文件夹 - 完成");
        }

        tileBoundTool = TileMath.TILE_BOUND_MAP.get(epsg);
        // 移除默认回退到3857的逻辑，如果epsg不存在应该报错
        if (tileBoundTool == null) {
            throw new IllegalArgumentException("Unsupported EPSG code: " + epsg);
        }

        sourceDs = gdal.Open(input);
        SpatialReference srcSrs = sourceDs.GetProjectionRef() != null ?
                new SpatialReference(sourceDs.GetProjectionRef()) : null;
        int srcEpsg = 0;
        if (srcSrs != null) {
            try {
                srcEpsg = Integer.parseInt(srcSrs.GetAuthorityCode(null));
            } catch (Exception ignored) {
            }
        }

        if (srcEpsg != epsg) {
            // 使用 reProjectFileName 或 UUID 作为文件名
            String reprojectFileName;
            if (reProjectFileName != null && !reProjectFileName.isEmpty() && !"UUID".equals(reProjectFileName)) {
                reprojectFileName = reProjectFileName;
            } else {
                reprojectFileName = IoHelper.uuid();
            }

            // 重投影文件放到 output 同级目录
            String reprojectPath = outputParentDir + File.separator + reprojectFileName + ".tif";
            projectPath = reprojectPath;

            // ============ 校验已有重投影文件是否可用 ============
            Dataset existingDs = tryOpenValidReprojection(reprojectPath, epsg);
            if (existingDs != null) {
                projectDs = existingDs;
                sourceDs.delete();
                Gir.log.info(">> 步骤" + (++stepIndex) + ": 复用已有重投影文件 " + projectPath + " - 完成");
            } else {
                // 执行重投影
                GdalHelper.reprojectImage(sourceDs, projectPath, epsg, resampling);
                projectDs = gdal.Open(projectPath);
                if (projectDs == null) {
                    throw new RuntimeException("重投影文件打开失败: " + projectPath);
                }
                sourceDs.delete();
                Gir.log.info(">> 步骤" + (++stepIndex) + ": 重投影至 EPSG:" + epsg + "，保存至 " + projectPath + " - 完成");
            }
        } else {
            projectDs = sourceDs;
            projectPath = sourceDs.GetDescription();
          Gir.log.info(">> 步骤" + (++stepIndex) + ": 源文件已是 EPSG:" + epsg + "，无需重投影");
        }
        sourceDs = null;

        // 构建金字塔（复用已有 .ovr，校验 GIS 范围一致）
        OverViewInfoResult overViewInfo = buildPyramid(projectDs, minZoom, resampling);
        Gir.log.info(">> 步骤" + (++stepIndex) + ": 构建影像金字塔索引 - 完成");
        if (projectPath != null) {
            String ovrPath = projectPath + ".ovr";
            File ovrFile = new File(ovrPath);
            if (ovrFile.exists()) {
              Gir.log.info("   金字塔文件位置: " + ovrPath);
            }
        }

        // 获取重投影后数据的实际坐标系
        SpatialReference destSrs = projectDs.GetProjectionRef() != null ?
                new SpatialReference(projectDs.GetProjectionRef()) : null;
        int destEpsg = 0;
        if (destSrs != null) {
            try {
                destEpsg = Integer.parseInt(destSrs.GetAuthorityCode(null));
            } catch (Exception ignored) {
                // 如果无法解析EPSG代码，使用传入的epsg
                destEpsg = epsg;
            }
        } else {
            // 如果没有投影信息，使用传入的epsg
            destEpsg = epsg;
        }

        DsInfo dsInfo = new DsInfo();
        double[] geoTransform = projectDs.GetGeoTransform();
        dsInfo.width = projectDs.GetRasterXSize();
        dsInfo.height = projectDs.GetRasterYSize();
        dsInfo.resX = geoTransform[1];
        dsInfo.resY = geoTransform[5];
        dsInfo.startX = geoTransform[0];
        dsInfo.startY = geoTransform[3];
        dsInfo.endX = geoTransform[0] + projectDs.GetRasterXSize() * geoTransform[1];
        dsInfo.endY = geoTransform[3] + projectDs.GetRasterYSize() * geoTransform[5];
        dsInfo.path = projectDs.GetDescription();

        double miny = Math.min(dsInfo.startY, dsInfo.endY);
        double maxy = Math.max(dsInfo.startY, dsInfo.endY);
        double[] startPoint = {dsInfo.startX, maxy};
        double[] endPoint = {dsInfo.endX, miny};

        // ============ 关键修复：使用实际的数据坐标系（destEpsg）而不是传入的epsg ============
        for (int tz = minZoom; tz <= maxZoom; tz++) {
            TileMath.TileRC minRC = TileMath.tileByCoordinate(startPoint, tz, destEpsg);
            TileMath.TileRC maxRC = TileMath.tileByCoordinate(endPoint, tz, destEpsg);
            tileCount.addAndGet((long) (maxRC.row - minRC.row + 1) * (maxRC.column - minRC.column + 1));
            LevelInfo info = new LevelInfo();
            info.tminx = minRC.column;
            info.tminy = minRC.row;
            info.tmaxx = maxRC.column;
            info.tmaxy = maxRC.row;
            levelInfo.put(tz, info);
        }

        ProgressBar progressBar = new ProgressBar(60, ">> 步骤" + (++stepIndex));
        progressBar.setTaskTotal(tileCount.get());

        // ============ 每线程按需打开独立 Dataset，避免 libtiff 并发 Seek 冲突 ============
        CreateTile.initDsPath(dsInfo.path);

        int coreSize = Math.min(Runtime.getRuntime().availableProcessors() * 2, 16);
        executorService = Executors.newFixedThreadPool(coreSize);

        CountDownLatch latch = new CountDownLatch((int) tileCount.get());
        String finalOutputDir = outputDir;
        boolean finalIsSaveMbtiles = isSaveMbtiles;
        String finalOutput = output;

        for (int tz = minZoom; tz <= maxZoom; tz++) {
            LevelInfo info = levelInfo.get(tz);
            OverviewInfo overviewInfo = getOverviewInfo(dsInfo, tz, overViewInfo);

            for (int j = info.tminx; j <= info.tmaxx; j++) {
                IoHelper.mkdirsSync(finalOutputDir + File.separator + tz + File.separator + j);
                for (int i = info.tminy; i <= info.tmaxy; i++) {

                    int finalTz = tz, finalJ = j, finalI = i;

                    // Mapbox / Terrarium：沿用原有 geoQuery 逻辑
                        double[] tileBound = TileMath.tileEnvelope(tz, j, i, BUFFER, destEpsg);
                        GeoQueryResult result = geoQuery(overviewInfo, tileBound[0], tileBound[1], tileBound[2], tileBound[3]);

                        CreateTile.ReadInfo readInfo = convertReadInfo(result.rb);
                        CreateTile.WriteInfo writeInfo = convertWriteInfo(result.wb);

                        executorService.submit(() -> {
                            try {
                                CreateTile.CreateInfo createInfo = new CreateTile.CreateInfo();
                                createInfo.outTileSize = TILE_SIZE + BUFFER * 2;
                                createInfo.overviewInfo = convertOverviewInfo(overviewInfo);
                                createInfo.rb = readInfo;
                                createInfo.wb = writeInfo;
                                createInfo.encoding = encoding;
                                createInfo.x = finalJ;
                                createInfo.y = finalI;
                                createInfo.z = finalTz;
                                createInfo.outputTile = finalOutputDir;

                                CreateTile.createTile(createInfo, (err, pid) -> {
                                    if (err != null)
                                        Gir.log.info("Error for tile z=" + finalTz + ", x=" + finalJ + ", y=" + finalI + ": " + err.getMessage());
                                    childPids.add((long) pid);
                                    long completed = completeCount.incrementAndGet();
                                    progressBar.render(completed);
                                    latch.countDown();
                                });
                            } catch (Exception e) {
                                Gir.log.info("Error in tile generation: " + e.getMessage());
                                e.printStackTrace();
                                latch.countDown();
                            }
                        });
                }
            }
        }

        latch.await();

        if (finalIsSaveMbtiles) {
            importMbtiles(finalOutputDir, finalOutput);
          Gir.log.info("\n>> 步骤" + (++stepIndex) + ": 转储mbtiles - 完成");
        }

        long endTime = System.currentTimeMillis();
        IoHelper.PrettyTimeResult timeResult = IoHelper.prettyTime(endTime - startTime);
        Gir.log.info("\n\n转换完成，用时 %.2f %s。%n", timeResult.resultTime, timeResult.unit);

        executorService.shutdown();
        executorService.awaitTermination(60, TimeUnit.SECONDS);
        recycle();
    }
    private static OverViewInfoResult buildPyramid(Dataset ds, int minZoom, int resampling) {
        String dsPath = ds.GetDescription();
        String ovrPath = dsPath + ".ovr";
        File ovrFile = new File(ovrPath);

        // ============ 校验已有金字塔是否可用（.ovr 存在 + 顶层 layer 小于等于 minZoom） ============
        if (ovrFile.exists()) {
            Band band = ds.GetRasterBand(1);
            if (band != null) {
                int overviewCount = band.GetOverviewCount();
                if (overviewCount > 0) {
                    double[] geoTransform = ds.GetGeoTransform();
                    double res = Math.abs(geoTransform[1]);

                    double resZoom = (tileBoundTool.xmax - tileBoundTool.xmin) / 256;
                    int originZ = 0;
                    while (resZoom / 2 > res) {
                        resZoom /= 2;
                        originZ++;
                    }

                    OverViewInfoResult result = new OverViewInfoResult();
                    result.maxOverViewsZ = originZ - 1;
                    result.minOverViewsZ = originZ - overviewCount;

                    // 已有金字塔的顶层（最粗糙）能覆盖 minZoom → 可直接复用
                    if (result.minOverViewsZ <= minZoom) {
                        Gir.log.info("  使用已存在的金字塔，Overview 数量: " + overviewCount);
                        return result;
                    }
                    Gir.log.info("  已存在金字塔层数不足（顶层 zoom=" + result.minOverViewsZ
                            + " > minZoom=" + minZoom + "），重新生成");
                }
            }
        }

        // ============ 生成金字塔 ============
        double[] geoTransform = ds.GetGeoTransform();
        double res = Math.abs(geoTransform[1]);
        int maxPixel = Math.min(ds.GetRasterXSize(), ds.GetRasterYSize());

        // 计算需要的金字塔层数
        int overviewNum = 0;
        int tempMaxPixel = maxPixel;
        while (tempMaxPixel > 256) {
            tempMaxPixel /= 2;
            overviewNum++;
        }
        overviewNum = Math.max(overviewNum, 1);

        // 计算原始数据的缩放级别
        double resZoom = (tileBoundTool.xmax - tileBoundTool.xmin) / 256;
        int originZ = 0;
        while (resZoom / 2 > res) {
            resZoom /= 2;
            originZ++;
        }

        // 生成金字塔因子（从2倍开始）
        List<Integer> overviews = new ArrayList<>();
        for (int i = 1; i <= overviewNum; i++) {
            overviews.add((int) Math.pow(2, i));
        }

        int[] overviewFactors = overviews.stream().mapToInt(Integer::intValue).toArray();

        if (overviewFactors.length > 0) {
            ds.FlushCache();
            ds.BuildOverviews(GdalHelper.getOverviewResamplingName(resampling), overviewFactors);
            ds.FlushCache();

            Band band = ds.GetRasterBand(1);
            if (band != null) {
                int actualOverviewCount = band.GetOverviewCount();
              Gir.log.info("  实际生成的 Overview 数量: " + actualOverviewCount);
                if (actualOverviewCount > 0 && actualOverviewCount < overviewNum) {
                    overviewNum = actualOverviewCount;
                }
            }
        }

        OverViewInfoResult result = new OverViewInfoResult();
        result.maxOverViewsZ = originZ - 1;
        result.minOverViewsZ = originZ - overviewNum;

      Gir.log.info("金字塔信息:");
      Gir.log.info("  - 原始数据对应缩放级别: " + originZ);
      Gir.log.info("  - 金字塔层数: " + overviewNum);
      Gir.log.info("  - 金字塔因子: " + Arrays.toString(overviewFactors));

        return result;
    }

    private static OverviewInfo getOverviewInfo(DsInfo dsInfo, int tz, OverViewInfoResult overViewInfo) {
        OverviewInfo info = new OverviewInfo();

        // 默认使用原始数据
        info.index = -1;
        info.startX = dsInfo.startX;
        info.startY = dsInfo.startY;
        info.width = dsInfo.width;
        info.height = dsInfo.height;
        info.resX = dsInfo.resX;
        info.resY = dsInfo.resY;

        // ============ 修复：移除 if(true) 返回，正确使用金字塔 ============
        // 检查是否有可用的 Overview
        if (overViewInfo.maxOverViewsZ > 0 && overViewInfo.minOverViewsZ > 0) {
            int maxIndex = overViewInfo.maxOverViewsZ - overViewInfo.minOverViewsZ;

            if (tz >= overViewInfo.minOverViewsZ && tz <= overViewInfo.maxOverViewsZ) {
                int index = overViewInfo.maxOverViewsZ - tz;
                if (index > maxIndex) index = maxIndex;
                if (index < 0) index = 0;

                int factor = (int) Math.pow(2, index + 1);

                int width = (int) Math.ceil(dsInfo.width * 1.0 / factor);
                int height = (int) Math.ceil(dsInfo.height * 1.0 / factor);
                width = Math.max(1, width);
                height = Math.max(1, height);

                info.index = index;
                info.width = width;
                info.height = height;
                info.resX = dsInfo.resX * factor;
                info.resY = dsInfo.resY * factor;

              Gir.log.info("  Zoom " + tz + " -> Overview index: " + index + "/" + maxIndex +
                                   ", factor: " + factor + ", size: " + width + "x" + height);
            } else if (tz < overViewInfo.minOverViewsZ) {
                int lastIndex = maxIndex;
                int factor = (int) Math.pow(2, lastIndex + 1);

                int width = (int) Math.ceil(dsInfo.width * 1.0 / factor);
                int height = (int) Math.ceil(dsInfo.height * 1.0 / factor);
                width = Math.max(1, width);
                height = Math.max(1, height);

                info.index = lastIndex;
                info.width = width;
                info.height = height;
                info.resX = dsInfo.resX * factor;
                info.resY = dsInfo.resY * factor;

              Gir.log.info("  Zoom " + tz + " -> 使用最粗略 Overview index: " + lastIndex +
                                   ", factor: " + factor + ", size: " + width + "x" + height);
            }
        }

        return info;
    }
    static class GeoQueryResult {
        ReadInfo rb;
        WriteInfo wb;
    }

    static class ReadInfo {
        int rx, ry, rxsize, rysize;
    }

    static class WriteInfo {
        int wx, wy, wxsize, wysize;
    }

    private static GeoQueryResult geoQuery(OverviewInfo overviewInfo, double ulx, double uly, double lrx, double lry) {
        int outTileSize = TILE_SIZE + BUFFER * 2;  // 258

        // 直接使用 overviewInfo 的 resX/resY（保持符号），与 Node 版本完全一致
        double startX = overviewInfo.startX;
        double startY = overviewInfo.startY;
        int width = overviewInfo.width;
        int height = overviewInfo.height;
        double resX = overviewInfo.resX;
        double resY = overviewInfo.resY;  // GDAL geoTransform[5] 通常为负值（北向朝上的图像）

        // ============ 与 Node geoQuery() 完全一致的像素坐标计算 ============
        int rx = (int) Math.floor((ulx - startX) / resX + 0.001);
        int ry = (int) Math.floor((uly - startY) / resY + 0.001);
        int rxsize = Math.max(1, (int) Math.floor((lrx - ulx) / resX + 0.5));
        int rysize = Math.max(1, (int) Math.floor((lry - uly) / resY + 0.5));

        int wxsize = outTileSize;
        int wysize = outTileSize;

        // ============ X 方向裁剪（与 Node 一致的比例裁剪算法） ============
        int wx = 0;
        if (rx < 0) {
            int rxshift = Math.abs(rx);
            wx = (int) Math.floor(wxsize * (rxshift * 1.0 / rxsize));
            wxsize = wxsize - wx;
            rxsize = rxsize - (int) Math.floor(rxsize * (rxshift * 1.0 / rxsize));
            rx = 0;
        }
        if ((rx + rxsize) > width) {
            wxsize = (int) Math.floor(wxsize * (width - rx) * 1.0 / rxsize);
            rxsize = width - rx;
        }

        // ============ Y 方向裁剪（与 Node 一致的比例裁剪算法） ============
        int wy = 0;
        if (ry < 0) {
            int ryshift = Math.abs(ry);
            wy = (int) Math.floor(wysize * (ryshift * 1.0 / rysize));
            wysize = wysize - wy;
            rysize = rysize - (int) Math.floor(rysize * (ryshift * 1.0 / rysize));
            ry = 0;
        }
        if ((ry + rysize) > height) {
            wysize = (int) Math.floor(wysize * (height - ry) * 1.0 / rysize);
            rysize = height - ry;
        }

        // ============ 安全边界检查 ============
        rx = Math.max(0, Math.min(rx, width - 1));
        ry = Math.max(0, Math.min(ry, height - 1));
        rxsize = Math.max(1, Math.min(rxsize, width - rx));
        rysize = Math.max(1, Math.min(rysize, height - ry));
        wx = Math.max(0, Math.min(wx, outTileSize - wxsize));
        wy = Math.max(0, Math.min(wy, outTileSize - wysize));
        wxsize = Math.max(1, Math.min(wxsize, outTileSize - wx));
        wysize = Math.max(1, Math.min(wysize, outTileSize - wy));

        return createResult(rx, ry, rxsize, rysize, wx, wy, wxsize, wysize);
    }

    private static GeoQueryResult createResult(int rx, int ry, int rxsize, int rysize,
                                               int wx, int wy, int wxsize, int wysize) {
        GeoQueryResult result = new GeoQueryResult();
        ReadInfo ri = new ReadInfo();
        ri.rx = rx;
        ri.ry = ry;
        ri.rxsize = rxsize;
        ri.rysize = rysize;
        WriteInfo wi = new WriteInfo();
        wi.wx = wx;
        wi.wy = wy;
        wi.wxsize = wxsize;
        wi.wysize = wysize;
        result.rb = ri;
        result.wb = wi;
        return result;
    }



    private static CreateTile.ReadInfo convertReadInfo(ReadInfo ri) {
        CreateTile.ReadInfo result = new CreateTile.ReadInfo();
        result.rx = ri.rx;
        result.ry = ri.ry;
        result.rxsize = ri.rxsize;
        result.rysize = ri.rysize;
        return result;
    }

    private static CreateTile.WriteInfo convertWriteInfo(WriteInfo wi) {
        CreateTile.WriteInfo result = new CreateTile.WriteInfo();
        result.wx = wi.wx;
        result.wy = wi.wy;
        result.wxsize = wi.wxsize;
        result.wysize = wi.wysize;
        return result;
    }

    private static CreateTile.OverviewInfo convertOverviewInfo(OverviewInfo info) {
        CreateTile.OverviewInfo result = new CreateTile.OverviewInfo();
        result.index = info.index;
        result.startX = info.startX;
        result.startY = info.startY;
        result.width = info.width;
        result.height = info.height;
        result.resX = info.resX;
        result.resY = info.resY;
        return result;
    }

    private static Dataset tryOpenValidReprojection(String tifPath, int expectedEpsg) {
        File f = new File(tifPath);
        if (!f.exists() || f.length() < 1024) {
            return null;
        }
        Dataset ds = gdal.Open(tifPath);
        if (ds == null) {
            return null;
        }
        try {
            // 校验坐标系
            SpatialReference srs = ds.GetProjectionRef() != null
                    ? new SpatialReference(ds.GetProjectionRef()) : null;
            if (srs == null) {
                ds.delete();
                return null;
            }
            int actualEpsg;
            try {
                actualEpsg = Integer.parseInt(srs.GetAuthorityCode(null));
            } catch (Exception ignored) {
                ds.delete();
                return null;
            }
            if (actualEpsg != expectedEpsg) {
                Gir.log.info("  已有文件 EPSG:" + actualEpsg + "，期望 EPSG:" + expectedEpsg + "，重新生成");
                ds.delete();
                return null;
            }
            // 校验数据是否可读（有有效波段）
            Band band = ds.GetRasterBand(1);
            if (band == null || band.getXSize() < 1) {
                ds.delete();
                return null;
            }
            return ds; // 有效，调用方接管生命周期
        } catch (Exception e) {
            try { ds.delete(); } catch (Exception ignored) {}
            return null;
        }
    }

    private static void importMbtiles(String tileDir, String mbtilesPath) throws Exception {
        try (MBTilesWriter mbtiles = MBTilesWriter.open(mbtilesPath, "rwc")) {
            mbtiles.startWriting();
            File dir = new File(tileDir);
            String[] zFolds = dir.list();
            if (zFolds == null) return;
            for (String zFold : zFolds) {
                int z = Integer.parseInt(zFold);
                File zPath = new File(tileDir, zFold);
                String[] xFolds = zPath.list();
                if (xFolds == null) continue;
                for (String xFold : xFolds) {
                    int x = Integer.parseInt(xFold);
                    File xPath = new File(zPath, xFold);
                    String[] yFiles = xPath.list();
                    if (yFiles == null) continue;
                    for (String yFile : yFiles) {
                        int y = Integer.parseInt(yFile.split("\\.")[0]);
                        File yPath = new File(xPath, yFile);
                        mbtiles.putTile(z, x, y, yPath.getAbsolutePath());
                    }
                }
            }
            mbtiles.stopWriting();
        }
        Files.walk(Paths.get(tileDir)).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }

    private static void recycle() {
        CreateTile.closeAll();
        if (sourceDs != null) {
            try {
                sourceDs.delete();
            } catch (Exception ignored) {}
            sourceDs = null;
        }
        if (projectDs != null) {
            try {
                projectDs.delete();
            } catch (Exception ignored) {}
            projectDs = null;
        }
        if (projectPath != null) {
            try {
              Gir.log.info("重投影文件保留在: " + projectPath);
                // 检查 .ovr 文件（GDAL 默认生成 .ovr 扩展名）
                String ovrPath = projectPath + ".ovr";
                File ovrFile = new File(ovrPath);
                if (ovrFile.exists()) {
                  Gir.log.info("金字塔文件保留在: " + ovrFile.getAbsolutePath());
                } else {
                    // 某些 GDAL 版本可能生成 .aux.xml
                    String auxPath = projectPath + ".aux.xml";
                    File auxFile = new File(auxPath);
                    if (auxFile.exists()) {
                      Gir.log.info("金字塔文件保留在: " + auxFile.getAbsolutePath());
                    } else {
                      Gir.log.info("警告: 未找到金字塔文件 (.ovr 或 .aux.xml)");
                    }
                }
            } catch (Exception e) {
                Gir.log.info("Warning: " + e.getMessage());
            }
            projectPath = null;
        }
        if (encodePath != null) {
            try {
                Files.deleteIfExists(Paths.get(encodePath));
            } catch (IOException ignored) {}
            encodePath = null;
        }
    }

    public static void verifyGdalEnvironment() {
        Gir.log.info("=== GDAL Environment ===");

        String gdalData = System.getenv("GDAL_DATA");
        String projData = System.getenv("PROJ_DATA");
        String path = System.getenv("PATH");

        Gir.log.info("GDAL_DATA: " + gdalData);
        Gir.log.info("PROJ_DATA: " + projData);

        if (gdalData != null) {
            File gdalDataDir = new File(gdalData);
            Gir.log.info("GDAL_DATA exists: " + gdalDataDir.exists());
            if (gdalDataDir.exists()) {
                File[] files = gdalDataDir.listFiles();
                if (files != null) {
                    Gir.log.info("Files in GDAL_DATA: " + files.length);
                    for (File f : files) {
                        if (f.getName().endsWith(".csv") || f.getName().endsWith(".wkt")) {
                            Gir.log.info("  - " + f.getName());
                        }
                    }
                }
            }
        }

        if (projData != null) {
            File projDb = new File(projData, "proj.db");
            Gir.log.info("proj.db exists: " + projDb.exists());
            if (projDb.exists()) {
                Gir.log.info("proj.db path: " + projDb.getAbsolutePath());
                Gir.log.info("proj.db size: " + projDb.length() + " bytes");
            }
        }

        if (path != null) {
            String[] pathDirs = path.split(File.pathSeparator);
            for (String dir : pathDirs) {
                if (dir.toLowerCase().contains("gdal") || dir.toLowerCase().contains("osgeo")) {
                    Gir.log.info("GDAL in PATH: " + dir);
                }
            }
        }
    }
}
