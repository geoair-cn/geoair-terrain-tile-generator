package cn.geoair.map.dynamic.terrain.ttg.png.model;

/**
 * 数据集信息
 * <p>
 * 存储 GDAL 数据集（通常是重投影后的 DEM）的元数据信息，
 * 用于瓦片生成时的坐标计算和像素读取。
 * <p>
 * 坐标系说明：
 * - 原点 (startX, startY) 是影像左上角的坐标
 * - X 方向从西向东（startX 到 endX）
 * - Y 方向从北向南（startY 到 endY，因为 resY 为负值）
 * <p>
 * 示例（新疆地区 DEM）：
 * - startX=73.5, endX=96.4: 经度范围
 * - startY=49.2, endY=34.3: 纬度范围（注意 startY > endY）
 * - width=92000, height=60000: 像素尺寸
 * - resX=0.00025, resY=-0.00025: 分辨率
 */
public class DsInfo {
    /**
     * 影像宽度（像素数）
     * <p>
     * 对应 X 方向（经度方向）的像素数量
     */
    public int width;

    /**
     * 影像高度（像素数）
     * <p>
     * 对应 Y 方向（纬度方向）的像素数量
     */
    public int height;

    /**
     * X 方向分辨率（每像素对应的经度度数）
     * <p>
     * 通常为正值，例如 0.00025 度/像素
     * 用于将像素坐标转换为地理坐标：lon = startX + pixelX * resX
     */
    public double resX;

    /**
     * Y 方向分辨率（每像素对应的纬度度数）
     * <p>
     * 通常为负值（因为影像从北向南排列），例如 -0.00025 度/像素
     * 用于将像素坐标转换为地理坐标：lat = startY + pixelY * resY
     */
    public double resY;

    /**
     * 影像左上角 X 坐标（西边界经度）
     * <p>
     * 通常是影像最西边的经度值
     */
    public double startX;

    /**
     * 影像左上角 Y 坐标（北边界纬度）
     * <p>
     * 通常是影像最北边的纬度值
     */
    public double startY;

    /**
     * 影像右下角 X 坐标（东边界经度）
     * <p>
     * 计算公式：endX = startX + width * resX
     */
    public double endX;

    /**
     * 影像右下角 Y 坐标（南边界纬度）
     * <p>
     * 计算公式：endY = startY + height * resY
     * 注意：因为 resY 为负值，所以 endY < startY
     */
    public double endY;

    /**
     * 数据集文件路径
     * <p>
     * 通常是重投影后的 GeoTIFF 文件路径
     */
    public String path;

    /**
     * 计算影像的地理范围宽度（经度跨度）
     *
     * @return 经度跨度（度）
     */
    public double getExtentWidth() {
        return Math.abs(endX - startX);
    }

    /**
     * 计算影像的地理范围高度（纬度跨度）
     *
     * @return 纬度跨度（度）
     */
    public double getExtentHeight() {
        return Math.abs(endY - startY);
    }

    @Override
    public String toString() {
        return String.format("DsInfo{size=%dx%d, bounds=[%.6f, %.6f, %.6f, %.6f], res=(%.8f, %.8f)}",
                width, height, startX, startY, endX, endY, resX, resY);
    }
}
