package cn.geoair.map.dynamic.terrain.ttg.model;

import org.gdal.gdal.Dataset;

/**
 * DEM 数据集元信息
 * <p>
 * 存储从 GDAL Dataset 中提取的地理元数据，用于瓦片生成时的坐标计算。
 * <p>
 * 包含以下信息：
 * - 地理范围：west(西经), south(南纬), east(东经), north(北纬)
 * - 原点坐标：originX(左上角X), originY(左上角Y)
 * - 分辨率：resolutionX(每像素X方向度数), resolutionY(每像素Y方向度数，通常为负值)
 * - 影像尺寸：width(宽度像素数), height(高度像素数)
 * <p>
 * 坐标系说明：
 * - 原点 (originX, originY) 是影像左上角的坐标
 * - resolutionX 为正值（从西向东）
 * - resolutionY 为负值（从北向南，因为影像行从上到下）
 * - 因此 south = originY + resolutionY * height（南边界在下方）
 */
public class DatasetInfo {
    private final double west;
    private final double south;
    private final double east;
    private final double north;
    private final double originX;
    private final double originY;
    private final double resolutionX;
    private final double resolutionY;
    private final int width;
    private final int height;

    public DatasetInfo(double west, double south, double east, double north,
                       double originX, double originY, double resolutionX, double resolutionY,
                       int width, int height) {
        this.west = west;
        this.south = south;
        this.east = east;
        this.north = north;
        this.originX = originX;
        this.originY = originY;
        this.resolutionX = resolutionX;
        this.resolutionY = resolutionY;
        this.width = width;
        this.height = height;
    }

    public double west() {
        return west;
    }

    public double south() {
        return south;
    }

    public double east() {
        return east;
    }

    public double north() {
        return north;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    public double resolutionX() {
        return resolutionX;
    }

    public double resolutionY() {
        return resolutionY;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * 从 GDAL Dataset 创建 DatasetInfo
     * <p>
     * 从数据集的 GeoTransform 中提取地理元信息。
     * <p>
     * GeoTransform 数组含义：
     * [0] originX: 左上角像素左上角的 X 坐标（经度）
     * [1] resolutionX: 像素宽度（经度方向，正值）
     * [2] 旋转参数（通常为0，北向上影像）
     * [3] originY: 左上角像素左上角的 Y 坐标（纬度）
     * [4] 旋转参数（通常为0，北向上影像）
     * [5] resolutionY: 像素高度（纬度方向，负值，因为从北向南）
     * <p>
     * 坐标计算：
     * - east = originX + resolutionX * width   (东边界)
     * - south = originY + resolutionY * height  (南边界，因为 resolutionY 为负)
     * <p>
     * 约束条件（Cesium 要求）：
     * - transform[2] == 0: 无旋转
     * - transform[4] == 0: 无旋转
     * - transform[1] > 0:  X 方向分辨率为正（从西向东）
     * - transform[5] < 0:  Y 方向分辨率为负（从北向南，北向上）
     *
     * @param dataset GDAL 数据集
     * @return DatasetInfo 对象
     * @throws IllegalArgumentException 如果影像有旋转或非北向上
     */
    public static DatasetInfo from(Dataset dataset) {
        double[] transform = dataset.GetGeoTransform();

        // 验证影像是否满足 Cesium 要求：北向上、无旋转
        if (transform[2] != 0 || transform[4] != 0 || transform[1] <= 0 || transform[5] >= 0) {
            throw new IllegalArgumentException(
                    String.format("Cesium 地形仅支持北向上、无旋转的 DEM。当前 GeoTransform: [%.6f, %.6f, %.6f, %.6f, %.6f, %.6f]",
                            transform[0], transform[1], transform[2], transform[3], transform[4], transform[5]));
        }

        // 计算地理范围
        // originX = transform[0], resolutionX = transform[1]
        // originY = transform[3], resolutionY = transform[5]（负值）
        double east = transform[0] + transform[1] * dataset.GetRasterXSize();
        double south = transform[3] + transform[5] * dataset.GetRasterYSize();

        return new DatasetInfo(
                transform[0],           // west: 左上角 X（西边界）
                south,                  // south: 计算得到的南边界
                east,                   // east: 计算得到的东边界
                transform[3],           // north: 左上角 Y（北边界）
                transform[0],           // originX: 原点 X（同 west）
                transform[3],           // originY: 原点 Y（同 north）
                transform[1],           // resolutionX: X 方向分辨率（正值）
                transform[5],           // resolutionY: Y 方向分辨率（负值）
                dataset.GetRasterXSize(), // width: 影像宽度（像素）
                dataset.GetRasterYSize()  // height: 影像高度（像素）
        );
    }
}
