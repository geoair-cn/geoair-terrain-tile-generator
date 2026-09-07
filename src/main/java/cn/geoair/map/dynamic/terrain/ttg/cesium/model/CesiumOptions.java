package cn.geoair.map.dynamic.terrain.ttg.cesium.model;

import cn.geoair.map.dynamic.terrain.ttg.cesium.CesiumTerrainGenerator.MeshPrecision;

/**
 * Cesium quantized-mesh 地形瓦片生成选项
 * <p>
 * 配置 Cesium 地形瓦片生成的所有参数，包括：
 * - 缩放级别范围（固定从 minZoom=0 到 maxZoom）
 * - 目标坐标系（当前仅支持 EPSG:4326）
 * - 输出控制（cleanOutput）
 * - 重采样方法（resampling）
 * - 重投影文件名（reprojectFileName）
 * - 网格精度（precision）
 * - 是否 gzip 压缩输出瓦片（gzip，默认 false）
 * <p>
 * 使用示例：
 * <pre>
 * // 使用默认配置
 * CesiumOptions options = CesiumOptions.standardOptions(12, "dem_4326");
 *
 * // 自定义配置
 * CesiumOptions options = new CesiumOptions(
 *     0,              // minZoom: 独立地形必须从 zoom 0 根瓦片开始
 *     15,             // maxZoom: 到 zoom 15
 *     4326,           // 目标坐标系 EPSG:4326
 *     true,           // 清空输出目录
 *     2,              // 重采样方法：bilinear
 *     "terrain_4326", // 重投影文件名
 *     MeshPrecision.HIGH, // 高精度网格 129x129
 *     false           // 不压缩，适合普通静态文件服务
 * );
 * </pre>
 * <p>
 * 缩放级别说明：
 * - zoom 0: EPSG:4326 下全球 2 个根瓦片
 * - zoom 5: 约 1024 个瓦片
 * - zoom 10: 约 100 万个瓦片
 * - zoom 15: 约 10 亿个瓦片（不建议直接生成）
 * <p>
 * 建议：
 * - 小范围数据：maxZoom <= 15
 * - 大范围数据：maxZoom <= 12
 * - 全球数据：maxZoom <= 8
 */
public class CesiumOptions {
    /**
     * 最小缩放级别（金字塔顶层）
     * 独立 terrain 服务固定为 0。Cesium 从第 0 级根瓦片开始遍历，
     * 若没有根瓦片，子级瓦片不会被请求。
     */
    private final int minZoom;

    /**
     * 最大缩放级别（金字塔底层）
     * 决定最精细的瓦片层级，范围：0-30
     * 值越大，瓦片越精细，但数量呈指数增长
     */
    private final int maxZoom;

    /**
     * 目标坐标系 EPSG 编码
     * 当前仅支持 4326 (WGS84)，因为 Cesium quantized-mesh 格式要求
     */
    private final int targetEpsg;

    /**
     * 是否清空输出目录
     * true: 生成前清空输出目录（推荐）
     * false: 保留已有文件，可能产生新旧混用问题
     */
    private final boolean cleanOutput;

    /**
     * 重采样方法
     * 用于重投影时的像素插值算法
     * 0=nearest, 1=average, 2=bilinear(推荐), 3=cubic, 4=cubicspline, 5=lanczos, 6=mode
     */
    private final int resampling;

    /**
     * 重投影输出文件名（不含扩展名）
     * <p>
     * 当源数据坐标系不是 EPSG:4326 时，需要重投影。
     * 重投影后的文件会保存为此名称 + ".tif"。
     * <p>
     * 特殊值：
     * - "UUID": 自动生成唯一文件名（默认）
     * - null 或空: 同 "UUID"
     * - 其他值: 使用指定的文件名
     * <p>
     * 文件位置：输出目录的父目录中
     */
    private final String reprojectFileName;

    /**
     * 网格精度
     * 决定每个瓦片的高程采样网格大小
     * LOW=33x33, MEDIUM=65x65, HIGH=129x129, ULTRA=257x257
     * 精度越高，地形细节越丰富，但文件越大
     */
    private final MeshPrecision precision;

    /**
     * 是否将整个 quantized-mesh 二进制瓦片 gzip 压缩。
     * 默认 false，关闭时可由普通静态文件服务直接提供；开启时服务端必须返回
     * {@code Content-Encoding: gzip}。
     */
    private final boolean gzip;

    /**
     * 创建 Cesium 地形生成选项
     *
     * @param minZoom           最小缩放级别（独立服务必须为 0）
     * @param maxZoom           最大缩放级别（0-30，必须 >= minZoom）
     * @param targetEpsg        目标坐标系（当前仅支持 4326）
     * @param cleanOutput       是否清空输出目录
     * @param resampling        重采样方法（0-6）
     * @param reprojectFileName 重投影文件名（"UUID" 表示自动生成）
     * @param precision         网格精度（LOW/MEDIUM/HIGH/ULTRA）
     * @throws IllegalArgumentException 参数无效时抛出
     */
    public CesiumOptions(int minZoom, int maxZoom, int targetEpsg, boolean cleanOutput,
                         int resampling, String reprojectFileName, MeshPrecision precision) {
        this(minZoom, maxZoom, targetEpsg, cleanOutput, resampling, reprojectFileName,
                precision, false);
    }

    /**
     * 创建 Cesium 地形生成选项。
     *
     * @param gzip 是否 gzip 压缩整个输出瓦片；默认建议为 false
     */
    public CesiumOptions(int minZoom, int maxZoom, int targetEpsg, boolean cleanOutput,
                         int resampling, String reprojectFileName, MeshPrecision precision,
                         boolean gzip) {
        // 参数验证
        if (minZoom != 0 || maxZoom > 30) {
            throw new IllegalArgumentException(
                    String.format("独立 Cesium 地形必须从第 0 级根瓦片开始，且 maxZoom 必须在 0-30，当前: minZoom=%d, maxZoom=%d",
                            minZoom, maxZoom));
        }
        if (targetEpsg != 4326) {
            throw new IllegalArgumentException("Cesium quantized-mesh 当前仅支持 EPSG:4326 输出，当前: " + targetEpsg);
        }

        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.targetEpsg = targetEpsg;
        this.cleanOutput = cleanOutput;
        this.resampling = resampling;
        this.reprojectFileName = reprojectFileName;
        this.precision = precision == null ? MeshPrecision.MEDIUM : precision;
        this.gzip = gzip;
    }

    /**
     * 获取最小缩放级别
     *
     * @return 最小缩放级别
     */
    public int minZoom() {
        return minZoom;
    }

    /**
     * 获取最大缩放级别
     *
     * @return 最大缩放级别
     */
    public int maxZoom() {
        return maxZoom;
    }

    /**
     * 获取目标坐标系 EPSG 编码
     *
     * @return EPSG 编码（当前固定返回 4326）
     */
    public int targetEpsg() {
        return targetEpsg;
    }

    /**
     * 是否清空输出目录
     *
     * @return true 表示清空
     */
    public boolean cleanOutput() {
        return cleanOutput;
    }

    /**
     * 获取重采样方法
     *
     * @return 重采样方法索引
     */
    public int resampling() {
        return resampling;
    }

    /**
     * 获取重投影文件名
     *
     * @return 文件名，"UUID" 表示自动生成
     */
    public String reprojectFileName() {
        return reprojectFileName;
    }

    /**
     * 获取网格精度
     *
     * @return MeshPrecision 枚举值
     */
    public MeshPrecision precision() {
        return precision;
    }

    /**
     * @return true 表示输出 gzip 压缩的 .terrain 文件
     */
    public boolean gzip() {
        return gzip;
    }

    /**
     * 创建默认的 Cesium 地形生成选项
     * <p>
     * 默认配置：
     * - 清空输出目录: true
     * - 重采样方法: bilinear (2)
     * - 网格精度: MEDIUM (65x65)
     * <p>
     * 使用示例：
     * <pre>
     * // 生成 zoom 0-12 的标准精度地形瓦片
     * CesiumOptions options = CesiumOptions.standardOptions(12, "dem_4326");
     * CesiumTerrainGenerator.generate("input.tif", "output_dir", options);
     * </pre>
     *
     * @param maxZoom           最大缩放级别
     * @param epsg              目标坐标系（必须为 4326）
     * @param reProjectFileName 重投影文件名（"UUID" 表示自动生成）
     * @return 默认配置的 CesiumOptions 对象
     */
    public static CesiumOptions defaultCesiumOptions(int maxZoom, int epsg, String reProjectFileName) {
        CesiumOptions options = new CesiumOptions(
                0,
                maxZoom,
                epsg,
                true,                    // cleanOutput: 清空输出目录
                2,                       // resampling: bilinear 双线性插值
                reProjectFileName,
                MeshPrecision.MEDIUM,    // precision: 65x65 网格
                false                    // gzip: 默认不压缩
        );
        return options;
    }

    /**
     * 创建默认选项，并可指定是否 gzip 压缩输出瓦片。
     */
    public static CesiumOptions defaultCesiumOptions(int maxZoom, int epsg,
                                                      String reProjectFileName, boolean gzip) {
        return new CesiumOptions(0, maxZoom, epsg, true, 2, reProjectFileName,
                MeshPrecision.MEDIUM, gzip);
    }

    /**
     * 快速预览选项：33 x 33 网格，适合快速检查 DEM 覆盖范围和整体地形。
     *
     * @param maxZoom 最大缩放级别
     * @param reProjectFileName 重投影文件名，"UUID" 表示自动生成
     * @return gzip 关闭的低精度选项
     */
    public static CesiumOptions lowOptions(int maxZoom, String reProjectFileName) {
        return createPreset(maxZoom, reProjectFileName, MeshPrecision.LOW);
    }

    /**
     * 标准生产选项：65 x 65 网格，是默认且推荐的平衡配置。
     *
     * @param maxZoom 最大缩放级别
     * @param reProjectFileName 重投影文件名，"UUID" 表示自动生成
     * @return gzip 关闭的标准精度选项
     */
    public static CesiumOptions standardOptions(int maxZoom, String reProjectFileName) {
        return createPreset(maxZoom, reProjectFileName, MeshPrecision.MEDIUM);
    }

    /**
     * 高精度选项：129 x 129 网格，适合山地和需要更细地形细节的区域。
     *
     * @param maxZoom 最大缩放级别
     * @param reProjectFileName 重投影文件名，"UUID" 表示自动生成
     * @return gzip 关闭的高精度选项
     */
    public static CesiumOptions highOptions(int maxZoom, String reProjectFileName) {
        return createPreset(maxZoom, reProjectFileName, MeshPrecision.HIGH);
    }

    /**
     * 超高精度选项：257 x 257 网格，仅建议用于范围较小且精度要求极高的 DEM。
     *
     * @param maxZoom 最大缩放级别
     * @param reProjectFileName 重投影文件名，"UUID" 表示自动生成
     * @return gzip 关闭的超高精度选项
     */
    public static CesiumOptions ultraOptions(int maxZoom, String reProjectFileName) {
        return createPreset(maxZoom, reProjectFileName, MeshPrecision.ULTRA);
    }

    private static CesiumOptions createPreset(int maxZoom, String reProjectFileName,
                                               MeshPrecision precision) {
        return new CesiumOptions(0, maxZoom, 4326, true, 2, reProjectFileName,
                precision, false);
    }
}
