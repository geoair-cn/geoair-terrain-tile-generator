package cn.geoair.map.dynamic.terrain.ttg.png.model;

/**
 * 影像金字塔构建结果
 * <p>
 * 存储 GDAL 影像金字塔 (Overview) 构建后的信息，
 * 用于确定在不同缩放级别下应该使用哪个金字塔层级。
 * <p>
 * 金字塔层级与缩放级别的对应关系：
 * <pre>
 * 缩放级别(zoom)    金字塔层级(index)    降采样倍数(factor)
 * ─────────────────────────────────────────────────────
 * maxOverViewsZ     0                    2x
 * maxOverViewsZ-1   1                    4x
 * maxOverViewsZ-2   2                    8x
 * ...              ...                   ...
 * minOverViewsZ     maxIndex             2^(maxIndex+1)x
 * </pre>
 * <p>
 * 使用示例：
 * - 如果 maxOverViewsZ=17, minOverViewsZ=13
 * - 则 zoom 17 使用 Overview 0 (2x)
 * - zoom 16 使用 Overview 1 (4x)
 * - zoom 15 使用 Overview 2 (8x)
 * - zoom 14 使用 Overview 3 (16x)
 * - zoom 13 使用 Overview 4 (32x)
 * - zoom < 13 使用最粗略的 Overview 4
 */
public class OverViewInfoResult {
    /**
     * 最大概览缩放级别（最精细的金字塔层级对应的缩放级别）
     * <p>
     * 该级别使用 Overview 0（2倍降采样）
     * 比该级别更精细的缩放级别将使用原始数据
     * <p>
     * 计算公式：maxOverViewsZ = originZ - 1
     * 其中 originZ 是原始数据对应的缩放级别
     */
    public int maxOverViewsZ;

    /**
     * 最小概览缩放级别（最粗糙的金字塔层级对应的缩放级别）
     * <p>
     * 该级别使用最后一个 Overview（最大降采样倍数）
     * 比该级别更粗糙的缩放级别也使用这个 Overview
     * <p>
     * 计算公式：minOverViewsZ = originZ - overviewCount
     * 其中 overviewCount 是生成的金字塔层数
     */
    public int minOverViewsZ;

    /**
     * 计算金字塔层级索引
     * <p>
     * 根据缩放级别返回应该使用的金字塔 Overview 索引
     *
     * @param zoom 缩放级别
     * @return 金字塔索引，-1 表示使用原始数据
     */
    public int getOverviewIndex(int zoom) {
        if (zoom > maxOverViewsZ) {
            return -1; // 使用原始数据
        }
        if (zoom < minOverViewsZ) {
            // 使用最粗略的 Overview
            return maxOverViewsZ - minOverViewsZ;
        }
        return maxOverViewsZ - zoom;
    }

    /**
     * 检查是否有可用的金字塔
     *
     * @return true 表示有金字塔可用
     */
    public boolean hasOverviews() {
        return maxOverViewsZ > 0 && minOverViewsZ > 0;
    }

    @Override
    public String toString() {
        return String.format("OverViewInfoResult{maxZ=%d, minZ=%d, 层数=%d}",
                maxOverViewsZ, minOverViewsZ, maxOverViewsZ - minOverViewsZ + 1);
    }
}
