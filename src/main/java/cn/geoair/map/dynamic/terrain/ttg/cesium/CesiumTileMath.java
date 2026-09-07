package cn.geoair.map.dynamic.terrain.ttg.cesium;

import cn.geoair.map.dynamic.terrain.ttg.model.Bounds;
import cn.geoair.map.dynamic.terrain.ttg.model.Range;

/**
 * Cesium 瓦片数学计算工具类
 * <p>
 * 提供 Cesium TMS (Tile Map Service) 瓦片坐标系的计算功能。
 * <p>
 * Cesium TMS 坐标系特点：
 * - X 轴：经度方向，范围 [-180, 180]，从西向东递增
 * - Y 轴：纬度方向，范围 [-90, 90]，从南向北递增（TMS 标准）
 * - 瓦片原点：左下角 (-180, -90)
 * <p>
 * 瓦片数量计算：
 * - X 方向瓦片数 = 2^(zoom+1)（因为经度范围是 360 度）
 * - Y 方向瓦片数 = 2^zoom（因为纬度范围是 180 度）
 * <p>
 * 示例（zoom=2）：
 * - X 方向：2^3 = 8 个瓦片，每个覆盖 45 度经度
 * - Y 方向：2^2 = 4 个瓦片，每个覆盖 45 度纬度
 */
final class CesiumTileMath {
    private CesiumTileMath() {
    }

    /**
     * 计算瓦片的地理范围（经纬度边界）
     * <p>
     * 根据瓦片的缩放级别和坐标，计算该瓦片覆盖的经纬度范围。
     * <p>
     * 计算公式：
     * - xTiles = 2^(zoom+1)  // X 方向总瓦片数
     * - yTiles = 2^zoom       // Y 方向总瓦片数
     * - width = 360 / xTiles  // 每个瓦片的经度跨度
     * - height = 180 / yTiles // 每个瓦片的纬度跨度
     * - west = -180 + x * width
     * - east = west + width
     * - south = -90 + tmsY * height
     * - north = south + height
     * <p>
     * 示例（zoom=2, x=1, tmsY=1）：
     * - xTiles = 8, yTiles = 4
     * - width = 45, height = 45
     * - west = -180 + 1*45 = -135
     * - east = -135 + 45 = -90
     * - south = -90 + 1*45 = -45
     * - north = -45 + 45 = 0
     * - 结果：覆盖 [-135, -45] 到 [-90, 0] 的区域
     *
     * @param zoom  缩放级别（0-30）
     * @param x     瓦片 X 坐标
     * @param tmsY  瓦片 Y 坐标（TMS 坐标系，从南向北递增）
     * @return 瓦片的地理范围
     * @throws IllegalArgumentException 如果坐标无效
     */
    static Bounds bounds(int zoom, int x, int tmsY) {
        int xTiles = 1 << (zoom + 1);  // X 方向总瓦片数 = 2^(zoom+1)
        int yTiles = 1 << zoom;        // Y 方向总瓦片数 = 2^zoom

        // 验证坐标范围
        if (x < 0 || x >= xTiles || tmsY < 0 || tmsY >= yTiles) {
            throw new IllegalArgumentException(
                    String.format("无效的 Cesium TMS 瓦片坐标: zoom=%d, x=%d, y=%d (有效范围: x=[0,%d], y=[0,%d])",
                            zoom, x, tmsY, xTiles - 1, yTiles - 1));
        }

        // 计算每个瓦片的经纬度跨度
        double width = 360.0 / xTiles;   // 经度跨度
        double height = 180.0 / yTiles;  // 纬度跨度

        // 计算瓦片边界
        double west = -180.0 + x * width;
        double east = west + width;
        double south = -90.0 + tmsY * height;

        return new Bounds(west, south, east, south + height);
    }

    /**
     * 计算指定地理范围在某个缩放级别下的瓦片坐标范围
     * <p>
     * 将经纬度范围转换为对应的瓦片坐标范围（TMS 坐标系）。
     * 这个方法用于确定需要生成哪些瓦片来覆盖给定的地理区域。
     * <p>
     * 计算公式（反向推导）：
     * - xTiles = 2^(zoom+1)
     * - yTiles = 2^zoom
     * - minX = floor((west + 180) / 360 * xTiles)
     * - maxX = floor((east + 180) / 360 * xTiles)
     * - minTmsY = floor((south + 90) / 180 * yTiles)
     * - maxTmsY = floor((north + 90) / 180 * yTiles)
     * <p>
     * 注意：结果会被 clamp 到有效范围 [0, xTiles-1] 和 [0, yTiles-1]
     *
     * @param west  西经（度）
     * @param south 南纬（度）
     * @param east  东经（度）
     * @param north 北纬（度）
     * @param zoom  缩放级别
     * @return 瓦片坐标范围 (minX, minY, maxX, maxY)
     */
    static Range range(double west, double south, double east, double north, int zoom) {
        int xTiles = 1 << (zoom + 1);  // X 方向总瓦片数
        int yTiles = 1 << zoom;        // Y 方向总瓦片数

        // 将经纬度转换为瓦片坐标（浮点数）
        // 公式：tileX = (lon + 180) / 360 * xTiles
        int minX = clamp((int) Math.floor((west + 180.0) / 360.0 * xTiles), 0, xTiles - 1);
        int maxX = clamp((int) Math.floor((east + 180.0) / 360.0 * xTiles), 0, xTiles - 1);
        int minTmsY = clamp((int) Math.floor((south + 90.0) / 180.0 * yTiles), 0, yTiles - 1);
        int maxTmsY = clamp((int) Math.floor((north + 90.0) / 180.0 * yTiles), 0, yTiles - 1);

        // 确保 min <= max（处理跨日期变更线等情况）
        return new Range(Math.min(minX, maxX), Math.min(minTmsY, maxTmsY),
                Math.max(minX, maxX), Math.max(minTmsY, maxTmsY));
    }

    /**
     * 将值限制在指定范围内
     *
     * @param value 输入值
     * @param min   最小值
     * @param max   最大值
     * @return 限制后的值
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
