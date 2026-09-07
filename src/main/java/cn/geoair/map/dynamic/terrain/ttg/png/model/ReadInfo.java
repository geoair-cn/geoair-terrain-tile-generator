package cn.geoair.map.dynamic.terrain.ttg.png.model;

/**
 * DEM 读取区域信息
 * <p>
 * 描述从 DEM (数字高程模型) 数据集中读取像素的区域。
 * 这些坐标是像素坐标，不是地理坐标。
 * <p>
 * 坐标系说明：
 * - 原点 (0, 0) 在影像左上角
 * - X 轴向右递增（列方向）
 * - Y 轴向下递增（行方向）
 * <p>
 * 示例：
 * <pre>
 * rx=1000, ry=2000: 从第 1000 列、第 2000 行开始读取
 * rxsize=512, rysize=512: 读取 512x512 像素的区域
 * </pre>
 * <p>
 * 注意事项：
 * - 坐标必须在有效范围内 [0, width-1] 和 [0, height-1]
 * - rx + rxsize 不能超过影像宽度
 * - ry + rysize 不能超过影像高度
 */
public class ReadInfo {
    /**
     * 读取起始 X 坐标（列号）
     * <p>
     * 从 0 开始，表示从第几列开始读取
     */
    public int rx;

    /**
     * 读取起始 Y 坐标（行号）
     * <p>
     * 从 0 开始，表示从第几行开始读取
     */
    public int ry;

    /**
     * 读取宽度（列数）
     * <p>
     * 表示要读取多少列像素
     */
    public int rxsize;

    /**
     * 读取高度（行数）
     * <p>
     * 表示要读取多少行像素
     */
    public int rysize;

    /**
     * 计算读取区域的像素总数
     *
     * @return 像素总数
     */
    public long getPixelCount() {
        return (long) rxsize * rysize;
    }

    @Override
    public String toString() {
        return String.format("ReadInfo{start=(%d,%d), size=%dx%d}", rx, ry, rxsize, rysize);
    }
}
