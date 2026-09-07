package cn.geoair.map.dynamic.terrain.ttg.png.model;

/**
 * 影像金字塔概览信息
 * <p>
 * 存储 GDAL 影像金字塔 (Overview) 的元数据，用于在不同缩放级别下
 * 选择合适的金字塔层级进行采样，提高瓦片生成效率。
 * <p>
 * 金字塔原理：
 * - 原始影像分辨率最高（最精细）
 * - 每级 Overview 将分辨率降低一半（2x, 4x, 8x...）
 * - 低缩放级别使用粗略的 Overview，避免读取过多像素
 * <p>
 * 示例：
 * - 原始影像: 10000x8000 像素，对应 zoom=18
 * - Overview 0 (2x): 5000x4000 像素，对应 zoom=17
 * - Overview 1 (4x): 2500x2000 像素，对应 zoom=16
 * - Overview 2 (8x): 1250x1000 像素，对应 zoom=15
 */
public class OverviewInfo {
    /**
     * 金字塔层级索引
     * <p>
     * -1 表示使用原始数据（无金字塔）
     * 0 表示第一个 Overview（2倍降采样）
     * 1 表示第二个 Overview（4倍降采样）
     * 以此类推
     */
    public int index;

    /**
     * 影像左上角 X 坐标（经度，单位：度）
     * <p>
     * 该值在所有金字塔层级中保持不变，
     * 因为金字塔只是改变分辨率，不改变地理范围
     */
    public double startX;

    /**
     * 影像左上角 Y 坐标（纬度，单位：度）
     * <p>
     * 通常为北纬（正值），因为影像从北向南排列
     */
    public double startY;

    /**
     * 影像宽度（像素）
     * <p>
     * 随金字塔层级增加而减小：
     * - 原始: 10000
     * - Overview 0: 5000
     * - Overview 1: 2500
     */
    public int width;

    /**
     * 影像高度（像素）
     * <p>
     * 随金字塔层级增加而减小
     */
    public int height;

    /**
     * X 方向分辨率（每像素对应的经度度数）
     * <p>
     * 通常为正值，例如 0.000278 度/像素
     * 随金字塔层级增加而增大（分辨率变粗）
     */
    public double resX;

    /**
     * Y 方向分辨率（每像素对应的纬度度数）
     * <p>
     * 通常为负值（因为影像从北向南排列），例如 -0.000278 度/像素
     * 随金字塔层级增加而增大（绝对值变大，分辨率变粗）
     */
    public double resY;

    @Override
    public String toString() {
        return String.format("OverviewInfo{index=%d, size=%dx%d, res=(%.8f, %.8f)}",
                index, width, height, resX, resY);
    }
}
