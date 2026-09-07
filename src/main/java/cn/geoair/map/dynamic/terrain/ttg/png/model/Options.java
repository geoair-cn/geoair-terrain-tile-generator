package cn.geoair.map.dynamic.terrain.ttg.png.model;

/**
 * PNG 地形瓦片生成配置选项
 * <p>
 * 用于配置地形瓦片生成过程中的所有参数，包括缩放级别、坐标系、编码方式等。
 * <p>
 * 使用示例：
 * <pre>
 * // 使用 Mapbox 编码
 * Options options = Options.defaultMapBoxOptions(0, 12, 4326, "terrain_4326");
 *
 * // 使用 Terrarium 编码
 * Options options = Options.defaultTerrariumOptions(0, 12, 4326, "terrain_4326");
 *
 * // 自定义配置
 * Options options = new Options(0, 15, 3857, "mapbox", 1, 2, "custom_name");
 * </pre>
 */
public class Options {
    /**
     * 最小缩放级别（瓦片金字塔最顶层）
     * <p>
     * 例如：0 表示全球范围，1 表示 2x2=4 个瓦片，2 表示 4x4=16 个瓦片
     * 范围通常为 0-20，值越小瓦片越少
     */
    public int minZoom;

    /**
     * 最大缩放级别（瓦片金字塔最底层）
     * <p>
     * 例如：10 表示最精细的级别
     * 范围通常为 0-20，值越大瓦片越精细，数量也越多
     * <p>
     * 瓦片数量随缩放级别指数增长：
     * - zoom 0: 1 个瓦片
     * - zoom 10: 约 100 万个瓦片
     * - zoom 15: 约 10 亿个瓦片
     */
    public int maxZoom;

    /**
     * 目标坐标系 EPSG 编码
     * <p>
     * 常用值：
     * - 4326: WGS84 经纬度坐标系（全球通用）
     * - 3857: Web Mercator 投影（Web 地图常用）
     * - 4490: CGCS2000 地理坐标系（中国）
     * <p>
     * 如果源数据坐标系与此不同，会自动进行重投影
     */
    public int epsg;

    /**
     * 是否清空输出目录
     * <p>
     * 1 = 清空输出目录（删除已有瓦片文件）
     * 0 = 保留并覆盖已有文件
     * <p>
     * 建议设置为 1 以避免新旧文件混用导致的问题
     */
    public int isClean;

    /**
     * 重采样方法
     * <p>
     * 用于重投影和构建金字塔时的像素插值算法
     * <p>
     * 可选值及对应含义：
     * <ul>
     * <li>0 = nearest (最近邻) - 速度最快，质量最差，适合分类数据</li>
     * <li>1 = bilinear (双线性) - 平衡速度和质量的常用选择</li>
     * <li>2 = cubic (三次卷积) - 质量较好，速度较慢</li>
     * <li>3 = cubicspline (三次样条) - 质量更好，速度更慢</li>
     * <li>4 = lanczos (Lanczos) - 质量最好，速度最慢，适合细节保留</li>
     * <li>5 = average (平均值) - 适合降采样</li>
     * <li>6 = mode (众数) - 适合分类数据降采样</li>
     * <li>7 = max (最大值)</li>
     * <li>8 = min (最小值)</li>
     * <li>9 = med (中值)</li>
     * <li>10 = q1 (第一四分位数)</li>
     * <li>11 = q3 (第三四分位数)</li>
     * </ul>
     * <p>
     * 建议：地形数据推荐使用 bilinear(1) 或 cubic(2) 以获得平滑效果
     */
    public int resampling;

    /**
     * 编码方式（地形数据 RGB 编码算法）
     * <p>
     * 将高程值编码为 RGB 像素，用于存储地形数据。
     * <p>
     * 可选值：
     * <ul>
     * <li>"mapbox" - Mapbox 编码方式
     *     <br>公式: R*256 + G + B/256 - 32768
     *     <br>优点: 兼容 Mapbox 地形服务规范</li>
     * <li>"terrarium" - Terrarium 编码方式
     *     <br>公式: (R * 256 + G + B / 256) - 32768
     *     <br>优点: 在某些 GIS 工具中兼容性更好</li>
     * </ul>
     */
    public String encoding;

    /**
     * 重投影输出文件名（不含扩展名）
     * <p>
     * 用途：当源数据坐标系与目标坐标系(epsg)不一致时，
     * 会先进行重投影，重投影后的文件将保存为此名称
     * <p>
     * 特殊值：
     * <ul>
     * <li>"UUID" 或不指定时 - 自动生成 UUID 作为文件名</li>
     * <li>其他值 - 使用指定的文件名</li>
     * </ul>
     * <p>
     * 示例：
     * <ul>
     * <li>"新疆地形_3857" → 生成 "新疆地形_3857.tif"</li>
     * <li>"UUID" → 生成 "a1b2c3d4-e5f6-7890-abcd-ef1234567890.tif"</li>
     * </ul>
     * <p>
     * 注意：重投影文件会保存在输出目录的父目录中，
     * 与瓦片输出目录平级，便于重复使用
     */
    public String reProjectFileName;

    /**
     * 构造地形瓦片生成配置选项
     *
     * @param minZoom           最小缩放级别，通常为 0
     * @param maxZoom           最大缩放级别，建议不超过 15（根据数据量调整）
     * @param epsg              目标坐标系 EPSG 编码，常用值：4326, 3857, 4490
     * @param encoding          编码方式："mapbox" 或 "terrarium"
     * @param isClean           是否清空输出目录：1=清空，0=保留
     * @param resampling        重采样方法：0-11 对应不同算法
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

    /**
     * 创建默认的 Mapbox 编码选项
     * <p>
     * 默认配置：
     * - 编码方式: mapbox
     * - 清空输出: 是
     * - 重采样: bilinear (双线性插值)
     *
     * @param minZoom           最小缩放级别
     * @param maxZoom           最大缩放级别
     * @param epsg              目标坐标系
     * @param reProjectFileName 重投影文件名
     * @return Mapbox 编码的配置选项
     */
    public static Options defaultMapBoxOptions(int minZoom, int maxZoom, int epsg, String reProjectFileName) {
        Options options = new Options(minZoom, maxZoom, epsg, "mapbox", 1, 1, reProjectFileName);
        return options;
    }

    /**
     * 创建默认的 Terrarium 编码选项
     * <p>
     * 默认配置：
     * - 编码方式: terrarium
     * - 清空输出: 是
     * - 重采样: bilinear (双线性插值)
     *
     * @param minZoom           最小缩放级别
     * @param maxZoom           最大缩放级别
     * @param epsg              目标坐标系
     * @param reProjectFileName 重投影文件名
     * @return Terrarium 编码的配置选项
     */
    public static Options defaultTerrariumOptions(int minZoom, int maxZoom, int epsg, String reProjectFileName) {
        Options options = new Options(minZoom, maxZoom, epsg, "terrarium", 1, 1, reProjectFileName);
        return options;
    }

    @Override
    public String toString() {
        return String.format("Options{zoom=%d-%d, epsg=%d, encoding='%s', clean=%d, resampling=%d}",
                minZoom, maxZoom, epsg, encoding, isClean, resampling);
    }
}
