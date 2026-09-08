package cn.geoair.map.dynamic.terrain.ttg.cesium.utils;

import cn.geoair.base.Gir;
import cn.geoair.map.dynamic.terrain.ttg.cesium.model.Bounds;
import cn.geoair.map.dynamic.terrain.ttg.cesium.model.CesiumOptions;
import cn.geoair.map.dynamic.terrain.ttg.cesium.model.DatasetInfo;
import cn.geoair.map.dynamic.terrain.ttg.cesium.model.Range;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconst;
import org.gdal.osr.SpatialReference;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cesium quantized-mesh 地形瓦片生成器
 * <p>
 * 功能：将 DEM (数字高程模型) 栅格数据转换为 Cesium 可用的 quantized-mesh 地形瓦片格式。
 * <p>
 * 核心流程：
 * 1. 打开源 DEM 文件并检查坐标系
 * 2. 如需重投影至 EPSG:4326（Cesium 仅支持 WGS84）
 * 3. 解析数据集信息（范围、分辨率等）
 * 4. 计算各缩放级别的瓦片范围（availability）
 * 5. 多线程并行生成瓦片（采样高程 -> 编码为 quantized-mesh -> 压缩为 gzip）
 * 6. 写入 layer.json 元数据文件
 * <p>
 * 输出格式：{outputDir}/{z}/{x}/{y}.terrain + layer.json
 * <p>
 * quantized-mesh 规范：https://github.com/CesiumGS/quantized-mesh
 */
public final class CesiumTerrainGenerator {
    static {
        gdal.AllRegister();
    }

    private CesiumTerrainGenerator() {
    }

    /**
     * 网格精度枚举，决定每个瓦片的高程采样网格大小
     * <p>
     * 网格越大，地形细节越丰富，但瓦片文件也越大
     * - LOW:    33x33  网格，适合低精度地形，文件最小
     * - MEDIUM: 65x65  网格，平衡精度与文件大小（默认）
     * - HIGH:   129x129 网格，高精度地形
     * - ULTRA:  257x257 网格，最高精度，文件最大
     */
    public enum MeshPrecision {
        LOW(33), MEDIUM(65), HIGH(129), ULTRA(257);
        private final int gridSize;

        MeshPrecision(int gridSize) {
            this.gridSize = gridSize;
        }

        public int gridSize() {
            return gridSize;
        }
    }

    /**
     * 生成 Cesium quantized-mesh 地形瓦片
     * <p>
     * 主入口方法，完整的处理流程包括：
     * 1. 参数校验与输出目录准备
     * 2. 打开源 DEM 数据集
     * 3. 坐标系检查与重投影（如需要）
     * 4. 解析数据集元信息
     * 5. 计算瓦片可用性范围
     * 6. 多线程并行生成各缩放级别的瓦片
     * 7. 写入 layer.json 元数据
     * 8. 资源清理
     *
     * @param input           源 DEM 文件路径（支持 GDAL 能识别的所有栅格格式：GeoTIFF、DEM 等）
     * @param outputDirectory 输出目录路径，瓦片将生成在此目录下的 {z}/{x}/{y}.terrain 结构中
     * @param options         生成选项，包含缩放级别、精度、重采样方法等配置
     * @throws Exception      生成过程中的任何错误
     */
    public static void generate(String input, String outputDirectory, CesiumOptions options) throws Exception {
        long startTime = System.currentTimeMillis();
        Gir.log.info("========================================");
        Gir.log.info("Cesium quantized-mesh 地形瓦片生成器");
        Gir.log.info("========================================");
        Gir.log.info("输入文件: {}", input);
        Gir.log.info("输出目录: {}", outputDirectory);
        Gir.log.info("缩放级别: {} - {}", options.minZoom(), options.maxZoom());
        Gir.log.info("网格精度: {} ({}x{})", options.precision(), options.precision().gridSize(), options.precision().gridSize());
        Gir.log.info("重采样方法: {}", options.resampling());
        Gir.log.info("是否清空输出: {}", options.cleanOutput());

        int stepIndex = 0;

        // ============ 步骤1: 参数校验 ============
        Gir.log.info(">> 步骤{}: 参数校验", ++stepIndex);
        File output = new File(outputDirectory);
        if (output.getName().toLowerCase().endsWith(".mbtiles")) {
            throw new IllegalArgumentException("Cesium quantized-mesh 不支持 MBTiles 输出格式");
        }

        // ============ 步骤2: 准备输出目录 ============
        Gir.log.info(">> 步骤{}: 准备输出目录", ++stepIndex);
        if (options.cleanOutput()) {
            Gir.log.info("  清空输出目录: {}", output.toPath());
            emptyDirectory(output.toPath());
        }
        Files.createDirectories(output.toPath());
        Gir.log.info("  输出目录已就绪: {}", output.getAbsolutePath());

        // ============ 步骤3: 打开源 DEM 数据集 ============
        Gir.log.info(">> 步骤{}: 打开源 DEM 数据集", ++stepIndex);
        Dataset inputDataset = gdal.Open(input);
        if (inputDataset == null) {
            throw new IllegalArgumentException("无法打开 DEM 文件: " + input);
        }
        Gir.log.info("  DEM 文件打开成功");
        Gir.log.info("  影像宽度: {} 像素", inputDataset.GetRasterXSize());
        Gir.log.info("  影像高度: {} 像素", inputDataset.GetRasterYSize());
        Gir.log.info("  波段数量: {}", inputDataset.GetRasterCount());

        Dataset workingDataset = null;
        String workingPath = null;
        long totalTiles = 0;
        long completedTiles = 0;

        try {
            // ============ 步骤4: 坐标系检查与重投影 ============
            Gir.log.info(">> 步骤{}: 坐标系检查与重投影", ++stepIndex);
            int sourceEpsg = authorityEpsg(inputDataset);
            Gir.log.info("  源数据坐标系: EPSG:{}", sourceEpsg > 0 ? sourceEpsg : "未知");

            if (sourceEpsg == 4326) {
                // 源数据已是 WGS84，无需重投影
                workingDataset = inputDataset;
                inputDataset = null;
                workingPath = workingDataset.GetDescription();
                Gir.log.info("  源数据已是 EPSG:4326，无需重投影");
            } else {
                // 需要重投影至 EPSG:4326（Cesium 仅支持 WGS84）
                Gir.log.info("  源数据非 EPSG:4326，需要重投影");
                File parent = output.getAbsoluteFile().getParentFile();
                String name = (options.reprojectFileName() == null || options.reprojectFileName().trim().isEmpty()
                               || "UUID".equalsIgnoreCase(options.reprojectFileName()))
                        ? java.util.UUID.randomUUID().toString() : options.reprojectFileName();
                workingPath = new File(parent, name + ".tif").getAbsolutePath();

                Gir.log.info("  重投影目标: EPSG:4326");
                Gir.log.info("  重投影输出: {}", workingPath);
                Gir.log.info("  重采样方法: {}", resamplingName(options.resampling()));

                long reprojectStart = System.currentTimeMillis();
                reprojectTo4326(inputDataset, workingPath, options.resampling());
                long reprojectTime = System.currentTimeMillis() - reprojectStart;

                workingDataset = gdal.Open(workingPath);
                if (workingDataset == null) {
                    throw new IllegalStateException("无法打开重投影结果: " + workingPath);
                }
                Gir.log.info("  重投影完成，耗时: {}ms", reprojectTime);
                Gir.log.info("  重投影后影像尺寸: {}x{}", workingDataset.GetRasterXSize(), workingDataset.GetRasterYSize());
            }

            // ============ 步骤5: 解析数据集元信息 ============
            Gir.log.info(">> 步骤{}: 解析数据集元信息", ++stepIndex);
            DatasetInfo datasetInfo = DatasetInfo.from(workingDataset);
            Gir.log.info("  数据范围:");
            Gir.log.info("    西经(West):  {}", String.format("%.6f", datasetInfo.west()));
            Gir.log.info("    东经(East):  {}", String.format("%.6f", datasetInfo.east()));
            Gir.log.info("    南纬(South): {}", String.format("%.6f", datasetInfo.south()));
            Gir.log.info("    北纬(North): {}", String.format("%.6f", datasetInfo.north()));
            Gir.log.info("  分辨率:");
            Gir.log.info("    X方向: {} 度/像素", String.format("%.8f", datasetInfo.resolutionX()));
            Gir.log.info("    Y方向: {} 度/像素", String.format("%.8f", Math.abs(datasetInfo.resolutionY())));
            Gir.log.info("  影像尺寸: {}x{} 像素", datasetInfo.width(), datasetInfo.height());

            // ============ 步骤6: 计算瓦片可用性范围 ============
            Gir.log.info(">> 步骤{}: 计算瓦片可用性范围 (Availability)", ++stepIndex);
            Map<Integer, Range> availability = buildAvailability(datasetInfo, options);
            Gir.log.info("  各缩放级别瓦片范围:");
            for (Map.Entry<Integer, Range> entry : availability.entrySet()) {
                int zoom = entry.getKey();
                Range range = entry.getValue();
                long levelTiles = (long) (range.maxX() - range.minX() + 1) * (range.maxY() - range.minY() + 1);
                totalTiles += levelTiles;
                Gir.log.info("    Zoom {}: X=[{}, {}] Y=[{}, {}] 瓦片数: {}",
                        String.format("%2d", zoom), range.minX(), range.maxX(), range.minY(), range.maxY(), levelTiles);
            }
            Gir.log.info("  总瓦片数: {}", totalTiles);

            // ============ 步骤7: 多线程并行生成瓦片 ============
            Gir.log.info(">> 步骤{}: 多线程并行生成瓦片", ++stepIndex);
            int workers = Math.min(Runtime.getRuntime().availableProcessors() * 2, 16);
            Gir.log.info("  并行线程数: {}", workers);
            Gir.log.info("  网格精度: {}x{}", options.precision().gridSize(), options.precision().gridSize());
            Gir.log.info("  开始生成瓦片...");

            ExecutorService executor = Executors.newFixedThreadPool(workers);
            long tileGenStart = System.currentTimeMillis();
            AtomicLong completedCount = new AtomicLong(0);
            AtomicLong errorCount = new AtomicLong(0);
            AtomicLong skippedCount = new AtomicLong(0);  // 跳过计数（无数据）

            try {
                List<Future<Void>> tasks = new ArrayList<>();
                for (Map.Entry<Integer, Range> entry : availability.entrySet()) {
                    int zoom = entry.getKey();
                    Range range = entry.getValue();
                    for (int x = range.minX(); x <= range.maxX(); x++) {
                        for (int y = range.minY(); y <= range.maxY(); y++) {
                            final int tileX = x, tileY = y;
                            tasks.add(executor.submit(new CesiumTileTask(workingPath, datasetInfo, output.toPath(),
                                    zoom, tileX, tileY, options.precision().gridSize(),
                                    options.gzip(), completedCount, errorCount, skippedCount, totalTiles)));
                        }
                    }
                }

                // 等待所有任务完成
                for (Future<Void> task : tasks) {
                    task.get();
                }

                long tileGenTime = System.currentTimeMillis() - tileGenStart;
                Gir.log.info("  瓦片生成完成!");
                Gir.log.info("  成功生成: {} 个", completedCount.get());
                if (skippedCount.get() > 0) {
                    Gir.log.info("  跳过(无数据): {} 个 (瓦片完全超出DEM范围)", skippedCount.get());
                }
                if (errorCount.get() > 0) {
                    Gir.log.info("  生成失败: {} 个 (编码异常)", errorCount.get());
                }
                Gir.log.info("  耗时: {}ms", tileGenTime);
                if (completedCount.get() > 0) {
                    Gir.log.info("  平均每个瓦片: {}ms", String.format("%.2f", (double) tileGenTime / completedCount.get()));
                }
            } finally {
                executor.shutdown();
            }

            // ============ 步骤8: 根据实际落盘瓦片生成 layer.json ============
            Gir.log.info(">> 步骤{}: 根据实际瓦片构建 Availability 并写入 layer.json", ++stepIndex);
            Map<Integer, List<Range>> actualAvailability =
                    buildActualAvailability(output.toPath(), options);
            writeLayerJson(output.toPath(), options, datasetInfo, actualAvailability);
            Gir.log.info("  layer.json 已生成: {}", output.toPath().resolve("layer.json").toAbsolutePath());

        } finally {
            // ============ 步骤9: 资源清理 ============
            Gir.log.info(">> 步骤{}: 资源清理", ++stepIndex);
            if (inputDataset != null) {
                inputDataset.delete();
                Gir.log.info("  已关闭源数据集");
            }
            if (workingDataset != null) {
                workingDataset.delete();
                Gir.log.info("  已关闭工作数据集");
            }
        }

        // ============ 完成 ============
        long totalTime = System.currentTimeMillis() - startTime;
        Gir.log.info("========================================");
        Gir.log.info("Cesium 地形瓦片生成完成!");
        Gir.log.info("========================================");
        Gir.log.info("输出目录: {}", output.getAbsolutePath());
        Gir.log.info("总瓦片数: {}", totalTiles);
        Gir.log.info("总耗时: {}", formatTime(totalTime));
        Gir.log.info("========================================");
    }

    /**
     * 构建各缩放级别的瓦片可用性范围
     * <p>
     * 根据数据集的地理范围和缩放级别，计算每个级别需要生成的瓦片坐标范围。
     * 使用 TMS (Tile Map Service) 坐标系，Y轴从南向北递增。
     * <p>
     * 计算逻辑：
     * - 将数据集的经纬度范围转换为对应缩放级别的瓦片坐标
     * - 返回 Map<缩放级别, 瓦片范围(最小X, 最小Y, 最大X, 最大Y)>
     *
     * @param info    数据集信息（包含经纬度范围）
     * @param options 生成选项（包含缩放级别范围）
     * @return 各缩放级别的瓦片范围映射表
     */
    private static Map<Integer, Range> buildAvailability(DatasetInfo info, CesiumOptions options) {
        Map<Integer, Range> result = new LinkedHashMap<>();
        for (int zoom = options.minZoom(); zoom <= options.maxZoom(); zoom++) {
            Range range = CesiumTileMath.range(info.west(), info.south(), info.east(), info.north(), zoom);
            result.put(zoom, range);
        }
        return result;
    }

    /**
     * 根据实际生成的文件构建瓦片可用性范围
     * <p>
     * 扫描输出目录，统计每个缩放级别实际生成的瓦片坐标范围。
     * 这样 layer.json 中的 available 只包含实际存在的瓦片，
     * 避免 Cesium 请求不存在的瓦片导致 404。
     * <p>
     * 目录结构：{output}/{z}/{x}/{y}.terrain
     *
     * @param output  输出目录
     * @param options 生成选项
     * @return 各缩放级别的实际瓦片范围
     */
    private static Map<Integer, List<Range>> buildActualAvailability(Path output, CesiumOptions options) {
        Map<Integer, List<Range>> result = new LinkedHashMap<>();

        for (int zoom = options.minZoom(); zoom <= options.maxZoom(); zoom++) {
            Path zoomDir = output.resolve(Integer.toString(zoom));
            if (!Files.isDirectory(zoomDir)) {
                continue;
            }

            // 每一个 X 保存实际存在的 Y。不能再用全局 min/max，否则不规则边界会被膨胀成大矩形。
            Map<Integer, List<Integer>> tilesByX = new TreeMap<>();
            File[] xDirs = zoomDir.toFile().listFiles();
            if (xDirs == null) {
                continue;
            }

            for (File xDir : xDirs) {
                if (!xDir.isDirectory()) {
                    continue;
                }

                final int x;
                try {
                    x = Integer.parseInt(xDir.getName());
                } catch (NumberFormatException ignored) {
                    continue;
                }

                File[] yFiles = xDir.listFiles();
                if (yFiles == null) {
                    continue;
                }

                List<Integer> ys = new ArrayList<>();
                for (File yFile : yFiles) {
                    if (!yFile.isFile()) {
                        continue;
                    }
                    String fileName = yFile.getName();
                    if (!fileName.endsWith(".terrain")) {
                        continue;
                    }
                    try {
                        ys.add(Integer.parseInt(fileName.substring(0, fileName.length() - ".terrain".length())));
                    } catch (NumberFormatException ignored) {
                        // 忽略非标准 terrain 文件名
                    }
                }

                if (!ys.isEmpty()) {
                    Collections.sort(ys);
                    tilesByX.put(x, ys);
                }
            }

            List<Range> levelRanges = new ArrayList<>();

            // 先按单个 X 将 Y 拆成真实的连续段。
            // 例如 y=[44,45,46,49] -> [44..46] + [49..49]，绝不虚构 47/48。
            for (Map.Entry<Integer, List<Integer>> entry : tilesByX.entrySet()) {
                int x = entry.getKey();
                List<Integer> ys = entry.getValue();
                if (ys.isEmpty()) {
                    continue;
                }

                int startY = ys.get(0);
                int previousY = startY;
                for (int i = 1; i < ys.size(); i++) {
                    int currentY = ys.get(i);
                    if (currentY == previousY) {
                        continue;
                    }
                    if (currentY == previousY + 1) {
                        previousY = currentY;
                        continue;
                    }

                    levelRanges.add(new Range(x, startY, x, previousY));
                    startY = currentY;
                    previousY = currentY;
                }
                levelRanges.add(new Range(x, startY, x, previousY));
            }

            // 为了绝对避免 404，这里不跨 X 合并。
            // layer.json 会稍大一点，但每个 rectangle 都和磁盘上的真实文件一一对应。
            if (!levelRanges.isEmpty()) {
                result.put(zoom, levelRanges);
                Gir.log.info("  Zoom {}: 实际 availability 矩形数 {}，X目录数 {}",
                        zoom, levelRanges.size(), tilesByX.size());
            }
        }

        return result;
    }

    /**
     * 写入 layer.json 元数据文件
     * <p>
     * layer.json 是 Cesium 地形服务的核心元数据文件，描述了：
     * - format: 瓦片格式（quantized-mesh-1.0）
     * - version: 版本号
     * - scheme: 瓦片坐标方案（tms）
     * - projection: 坐标系（EPSG:4326）
     * - minzoom/maxzoom: 缩放级别范围
     * - tiles: 瓦片URL模板
     * - bounds: 数据地理范围
     * - available: 各级别的瓦片范围
     *
     * @param output       输出目录
     * @param options      生成选项
     * @param info         数据集信息
     * @param availability 瓦片可用性范围
     * @throws IOException 文件写入错误
     */
    private static void writeLayerJson(Path output, CesiumOptions options, DatasetInfo info,
                                       Map<Integer, List<Range>> availability) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode root = mapper.createObjectNode();


        root.put("name", "GeoAir Terrain");
        root.put("description", "Cesium quantized-mesh terrain generated by GeoAir Terrain Tile Generator");
        root.put("attribution", "Generated by GeoAir Terrain Tile Generator");

        // 基本格式信息
        root.put("format", "quantized-mesh-1.0");  // Cesium quantized-mesh 格式标识
        root.put("version", "1.0.0");               // 格式版本
        root.put("scheme", "tms");                   // TMS 坐标系（标准规范，兼容性最好）
        root.put("projection", "EPSG:4326");         // WGS84 坐标系

        // 缩放级别范围
        root.put("minzoom", options.minZoom());
        root.put("maxzoom", options.maxZoom());

        // 瓦片URL模板，{z}/{x}/{y} 会被替换为实际值
        root.putArray("tiles").add("{z}/{x}/{y}.terrain");

        // 数据地理范围 [west, south, east, north]
        root.putArray("bounds").add(info.west()).add(info.south()).add(info.east()).add(info.north());

        // available 的数组下标就是 zoom level，因此必须保留空级别，不能只遍历 Map.values()。
        ArrayNode available = root.putArray("available");
        for (int zoom = 0; zoom <= options.maxZoom(); zoom++) {
            ArrayNode level = available.addArray();
            List<Range> ranges = availability.get(zoom);
            if (ranges == null) {
                continue;
            }
            for (Range range : ranges) {
                level.addObject()
                        .put("startX", range.minX())
                        .put("startY", range.minY())
                        .put("endX", range.maxX())
                        .put("endY", range.maxY());
            }
        }

        // 写入文件
        Path layerJsonPath = output.resolve("layer.json");
        mapper.writeValue(layerJsonPath.toFile(), root);
        Gir.log.info("  layer.json 内容概要:");
        Gir.log.info("    格式: quantized-mesh-1.0");
        Gir.log.info("    坐标系: EPSG:4326");
        Gir.log.info("    方案: tms (Tile Map Service)");
        Gir.log.info("    缩放级别: {} - {}", options.minZoom(), options.maxZoom());
        Gir.log.info("    数据范围: [{}, {}, {}, {}]",
                info.west(), info.south(), info.east(), info.north());
    }

    /**
     * 重投影数据集至 EPSG:4326 (WGS84)
     * <p>
     * Cesium 地形服务仅支持 WGS84 坐标系，因此非 4326 数据必须先重投影。
     * 使用 GDAL 的 AutoCreateWarpedVRT 进行重投影，保留原始数据的精度。
     * <p>
     * 流程：
     * 1. 创建目标坐标系 (EPSG:4326)
     * 2. 使用 AutoCreateWarpedVRT 创建重投影 VRT
     * 3. 将 VRT 复制为 GeoTIFF 文件
     *
     * @param source      源数据集
     * @param destination 重投影输出文件路径
     * @param resampling  重采样方法（0=nearest, 1=bilinear, 2=cubic 等）
     */
    private static void reprojectTo4326(Dataset source, String destination, int resampling) {
        Gir.log.info("  开始重投影...");
        Gir.log.info("    源坐标系: {}", source.GetProjectionRef() != null ? "已定义" : "未定义");
        Gir.log.info("    目标坐标系: EPSG:4326");
        Gir.log.info("    重采样方法: {}", resamplingName(resampling));

        // 创建目标坐标系
        SpatialReference target = new SpatialReference();
        target.ImportFromEPSG(4326);

        // 获取源坐标系
        SpatialReference current = source.GetProjectionRef() == null ? null : new SpatialReference(source.GetProjectionRef());

        // 创建重投影 VRT（虚拟栅格）
        Dataset warped = gdal.AutoCreateWarpedVRT(source,
                current == null ? null : current.ExportToWkt(),
                target.ExportToWkt(),
                gdalResampling(resampling));
        if (warped == null) {
            throw new IllegalStateException("无法创建 EPSG:4326 重投影 VRT");
        }
        Gir.log.info("    VRT 创建成功，尺寸: {}x{}", warped.GetRasterXSize(), warped.GetRasterYSize());

        try {
            // 将 VRT 复制为实际的 GeoTIFF 文件
            Driver driver = source.GetDriver();
            Gir.log.info("    使用驱动: {}", driver.getShortName());
            Dataset copied = driver.CreateCopy(destination, warped);
            if (copied == null) {
                throw new IllegalStateException("无法写入重投影 TIFF: " + destination);
            }
            copied.delete();
            Gir.log.info("    重投影文件已保存: {}", destination);
        } finally {
            warped.delete();
        }
    }

    /**
     * 将用户选择的重采样方法索引转换为 GDAL 常量
     * <p>
     * 重采样方法选择指南：
     * - nearest (0): 最近邻，速度最快，适合分类数据
     * - average (1): 平均值，适合降采样
     * - bilinear (2): 双线性插值，平衡速度和质量（推荐用于地形）
     * - cubic (3): 三次卷积，质量较好
     * - cubicspline (4): 三次样条，更平滑
     * - lanczos (5): Lanczos，锐度最高
     * - mode (6): 众数，适合分类数据降采样
     * - nearest (7): 最近邻（同0）
     *
     * @param value 用户选择的重采样方法索引
     * @return GDAL 重采样常量
     */
    private static int gdalResampling(int value) {
        switch (value) {
            case 1:
                return gdalconst.GRA_Average;     // 平均值
            case 2:
                return gdalconst.GRA_Bilinear;    // 双线性插值（推荐）
            case 3:
                return gdalconst.GRA_Cubic;       // 三次卷积
            case 4:
                return gdalconst.GRA_CubicSpline; // 三次样条
            case 5:
                return gdalconst.GRA_Lanczos;     // Lanczos
            case 6:
                return gdalconst.GRA_Mode;        // 众数
            case 7:
                return gdalconst.GRA_NearestNeighbour; // 最近邻
            default:
                return gdalconst.GRA_Cubic;       // 默认三次卷积
        }
    }

    /**
     * 获取重采样方法的名称描述
     *
     * @param resampling 重采样方法索引
     * @return 方法名称
     */
    private static String resamplingName(int resampling) {
        switch (resampling) {
            case 0: return "nearest (最近邻)";
            case 1: return "average (平均值)";
            case 2: return "bilinear (双线性插值)";
            case 3: return "cubic (三次卷积)";
            case 4: return "cubicspline (三次样条)";
            case 5: return "lanczos (Lanczos)";
            case 6: return "mode (众数)";
            case 7: return "nearest (最近邻)";
            default: return "cubic (三次卷积，默认)";
        }
    }

    /**
     * 获取数据集的 EPSG 坐标系代码
     * <p>
     * 从数据集的投影信息中解析 EPSG 代码。
     * 如果数据集没有投影信息或解析失败，返回 0。
     *
     * @param dataset GDAL 数据集
     * @return EPSG 代码，失败返回 0
     */
    private static int authorityEpsg(Dataset dataset) {
        if (dataset.GetProjectionRef() == null || dataset.GetProjectionRef().trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(new SpatialReference(dataset.GetProjectionRef()).GetAuthorityCode(null));
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * 清空目录中的所有文件和子目录
     * <p>
     * 递归删除目录下的所有内容，但保留目录本身。
     * 用于在生成新瓦片前清理旧的输出。
     *
     * @param directory 要清空的目录路径
     * @throws IOException 文件删除错误
     */
    private static void emptyDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        Gir.log.info("  清空目录: {}", directory);
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path current, IOException exception) throws IOException {
                Files.delete(current);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Cesium 瓦片生成任务
     * <p>
     * 每个任务负责生成一个瓦片（z/x/y），包括：
     * 1. 打开数据集（每个线程独立打开，避免并发问题）
     * 2. 采样高程数据（从 DEM 中读取瓦片范围内的高程值）
     * 3. 编码为 quantized-mesh 格式（量化顶点、构建三角网、压缩）
     * 4. 写入 .terrain 文件
     * <p>
     * quantized-mesh 格式特点：
     * - 顶点坐标量化为 0-32767 范围
     * - 使用 high-water-mark 编码压缩三角网索引
     * - 使用 gzip 压缩整个瓦片
     */
    private static class CesiumTileTask implements Callable<Void> {
        private final String datasetPath;  // 数据集文件路径（每个线程独立打开）
        private final DatasetInfo dataset; // 数据集元信息
        private final Path output;         // 输出目录
        private final int zoom;            // 缩放级别
        private final int x;               // 瓦片 X 坐标
        private final int y;               // 瓦片 Y 坐标（TMS 坐标系）
        private final int gridSize;        // 采样网格大小（如 65x65）
        private final boolean gzip;              // 是否 gzip 压缩输出瓦片
        private final AtomicLong completedCount; // 完成计数器
        private final AtomicLong errorCount;     // 错误计数器
        private final AtomicLong skippedCount;   // 跳过计数器（无数据）
        private final long totalTiles;           // 总瓦片数（用于进度显示）

        CesiumTileTask(String datasetPath, DatasetInfo dataset, Path output, int zoom,
                       int x, int y, int gridSize, boolean gzip,
                       AtomicLong completedCount, AtomicLong errorCount, AtomicLong skippedCount, long totalTiles) {
            this.datasetPath = datasetPath;
            this.dataset = dataset;
            this.output = output;
            this.zoom = zoom;
            this.x = x;
            this.y = y;
            this.gridSize = gridSize;
            this.gzip = gzip;
            this.completedCount = completedCount;
            this.errorCount = errorCount;
            this.skippedCount = skippedCount;
            this.totalTiles = totalTiles;
        }

        @Override
        public Void call() throws Exception {
            // 每个线程独立打开数据集，避免 libtiff 并发 Seek 冲突
            Dataset source = gdal.Open(datasetPath);
            if (source == null) {
                throw new IllegalStateException("工作线程无法打开 DEM: " + datasetPath);
            }
            try {
                // 计算瓦片地理范围（使用 TMS Y 坐标）
                Bounds bounds = CesiumTileMath.bounds(zoom, x, y);

                // 从 DEM 中采样高程数据
                float[][] heights = sample(source.GetRasterBand(1), bounds, dataset, gridSize);

                // 编码为 quantized-mesh 格式
                byte[] tile = CesiumQuantizedMeshEncoder.encode(heights, bounds, gzip);

                // 全 NaN 表示该瓦片没有任何有效 DEM 数据：不要制造 0m 空地形。
                // 不落盘后，最终的 actualAvailability 也不会把它声明为可用。
                if (tile == null) {
                    long skipped = skippedCount.incrementAndGet();
                    long processed = completedCount.get() + skipped + errorCount.get();
                    if (processed % 100 == 0 || processed == totalTiles) {
                        Gir.log.info("  进度: {}/{} ({}%)，生成={} 跳过={} 失败={}",
                                processed, totalTiles,
                                String.format("%.1f", (double) processed / totalTiles * 100),
                                completedCount.get(), skippedCount.get(), errorCount.get());
                    }
                    return null;
                }

                Path target = output.resolve(Integer.toString(zoom))
                        .resolve(Integer.toString(x))
                        .resolve(y + ".terrain");
                Files.createDirectories(target.getParent());
                Files.write(target, tile);

                long completed = completedCount.incrementAndGet();
                long processed = completed + skippedCount.get() + errorCount.get();
                if (processed % 100 == 0 || processed == totalTiles) {
                    Gir.log.info("  进度: {}/{} ({}%)，生成={} 跳过={} 失败={}",
                            processed, totalTiles,
                            String.format("%.1f", (double) processed / totalTiles * 100),
                            completedCount.get(), skippedCount.get(), errorCount.get());
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
                Gir.log.warn("  瓦片生成异常 z={}/x={}/y={}: {}", zoom, x, y, e.getMessage());
            } finally {
                source.delete();
            }
            return null;
        }
    }

    /**
     * 从 DEM 波段中采样高程数据
     * <p>
     * 将瓦片的地理范围映射到 DEM 像素坐标，读取指定大小的高程网格。
     * 使用 GDAL 的 ReadRaster 进行重采样，自动处理不同分辨率的情况。
     * <p>
     * 坐标转换流程：
     * 1. 瓦片地理范围 (west, south, east, north) -> DEM 像素坐标 (readX, readY)
     * 2. 计算读取范围 (readEndX - readX, readEndY - readY)
     * 3. GDAL ReadRaster 将读取范围重采样为 gridSize x gridSize 的网格
     * <p>
     * 特殊处理：
     * - 超出 DEM 范围的像素会被裁剪
     * - NoData 值会被转换为 Float.NaN
     * - 如果瓦片完全超出 DEM 范围，返回全 NaN 网格
     *
     * @param band     DEM 波段
     * @param bounds   瓦片地理范围
     * @param info     数据集元信息
     * @param gridSize 采样网格大小（如 65 表示 65x65 网格）
     * @return 高程值二维数组 [row][column]，无效值为 Float.NaN
     */
    private static float[][] sample(Band band, Bounds bounds, DatasetInfo info, int gridSize) {
        float[][] result = new float[gridSize][gridSize];
        for (float[] row : result) {
            java.util.Arrays.fill(row, Float.NaN);
        }

        // 先算 tile 与 DEM 的真实地理交集。
        double intersectWest = Math.max(bounds.west(), info.west());
        double intersectEast = Math.min(bounds.east(), info.east());
        double intersectSouth = Math.max(bounds.south(), info.south());
        double intersectNorth = Math.min(bounds.north(), info.north());

        if (intersectEast <= intersectWest || intersectNorth <= intersectSouth) {
            return result;
        }

        double tileWidth = bounds.east() - bounds.west();
        double tileHeight = bounds.north() - bounds.south();
        if (tileWidth <= 0.0 || tileHeight <= 0.0) {
            return result;
        }

        // 将地理交集映射到输出 grid 的对应子区域。
        // 关键点：只填交集所占的格子，不能把交集 DEM 拉伸到整个 tile。
        int colStart = clampGridIndex((int) Math.ceil(
                (intersectWest - bounds.west()) / tileWidth * (gridSize - 1) - 1e-10), gridSize);
        int colEnd = clampGridIndex((int) Math.floor(
                (intersectEast - bounds.west()) / tileWidth * (gridSize - 1) + 1e-10), gridSize);
        int rowStart = clampGridIndex((int) Math.ceil(
                (bounds.north() - intersectNorth) / tileHeight * (gridSize - 1) - 1e-10), gridSize);
        int rowEnd = clampGridIndex((int) Math.floor(
                (bounds.north() - intersectSouth) / tileHeight * (gridSize - 1) + 1e-10), gridSize);

        if (colEnd < colStart || rowEnd < rowStart) {
            return result;
        }

        int outWidth = colEnd - colStart + 1;
        int outHeight = rowEnd - rowStart + 1;

        // 使用输出子网格首尾采样点对应的真实经纬度计算源读取窗口。
        double sampleWest = bounds.west() + colStart * tileWidth / (gridSize - 1.0);
        double sampleEast = bounds.west() + colEnd * tileWidth / (gridSize - 1.0);
        double sampleNorth = bounds.north() - rowStart * tileHeight / (gridSize - 1.0);
        double sampleSouth = bounds.north() - rowEnd * tileHeight / (gridSize - 1.0);

        double resX = info.resolutionX();
        double resY = info.resolutionY();
        if (resX == 0.0 || resY == 0.0) {
            return result;
        }

        int readX = Math.max(0, (int) Math.floor((sampleWest - info.originX()) / resX));
        int readY = Math.max(0, (int) Math.floor((sampleNorth - info.originY()) / resY));

        // +1 让单点/窄交集也至少覆盖一个源像素。
        int readEndX = Math.min(info.width(),
                (int) Math.ceil((sampleEast - info.originX()) / resX) + 1);
        int readEndY = Math.min(info.height(),
                (int) Math.ceil((sampleSouth - info.originY()) / resY) + 1);

        if (readEndX <= readX || readEndY <= readY) {
            return result;
        }

        float[] values = new float[outWidth * outHeight];
        band.ReadRaster(
                readX, readY,
                readEndX - readX, readEndY - readY,
                outWidth, outHeight,
                gdalconst.GDT_Float32,
                values
        );

        Double[] noData = new Double[1];
        band.GetNoDataValue(noData);
        Double noDataValue = noData[0];

        for (int row = 0; row < outHeight; row++) {
            for (int col = 0; col < outWidth; col++) {
                float value = values[row * outWidth + col];
                boolean invalid = Float.isNaN(value) || Float.isInfinite(value);
                if (!invalid && noDataValue != null) {
                    // 对浮点 NoData 使用很小容差，避免重采样后的表示误差。
                    double tolerance = Math.max(1e-6, Math.abs(noDataValue) * 1e-7);
                    invalid = Math.abs(value - noDataValue) <= tolerance;
                }
                result[rowStart + row][colStart + col] = invalid ? Float.NaN : value;
            }
        }

        return result;
    }

    private static int clampGridIndex(int value, int gridSize) {
        return Math.max(0, Math.min(gridSize - 1, value));
    }

    /**
     * 格式化时间长度
     *
     * @param millis 毫秒数
     * @return 格式化的时间字符串
     */
    private static String formatTime(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.2fs", millis / 1000.0);
        } else {
            long minutes = millis / 60000;
            long seconds = (millis % 60000) / 1000;
            return String.format("%dm %ds", minutes, seconds);
        }
    }
}
