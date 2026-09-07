package cn.geoair.map.dynamic.terrain.ttg.png.model;

/**
 * 瓦片写入区域信息
 * <p>
 * 描述将 DEM 数据写入到瓦片图像时的偏移和尺寸信息。
 * <p>
 * 使用场景：
 * 当瓦片只部分覆盖 DEM 时，需要将读取的数据写入到瓦片的特定位置，
 * 而不是从瓦片左上角开始写入。
 * <p>
 * 坐标系说明：
 * - 原点 (0, 0) 在瓦片左上角
 * - X 轴向右递增（列方向）
 * - Y 轴向下递增（行方向）
 * <p>
 * 示例：
 * <pre>
 * 瓦片大小: 258x258 (256 + 2*1 buffer)
 * DEM 只覆盖瓦片的右半部分
 *
 * 结果:
 * - wx=129, wy=0: 从瓦片的第 129 列开始写入
 * - wxsize=129, wysize=258: 写入 129x258 像素
 * </pre>
 */
public class WriteInfo {
    /**
     * 写入起始 X 坐标（列号）
     * <p>
     * 表示从瓦片的第几列开始写入
     * 通常为 0，当瓦片部分超出 DEM 时可能大于 0
     */
    public int wx;

    /**
     * 写入起始 Y 坐标（行号）
     * <p>
     * 表示从瓦片的第几行开始写入
     * 通常为 0，当瓦片部分超出 DEM 时可能大于 0
     */
    public int wy;

    /**
     * 写入宽度（列数）
     * <p>
     * 表示要写入多少列像素
     * 最大值为瓦片宽度（通常 258）
     */
    public int wxsize;

    /**
     * 写入高度（行数）
     * <p>
     * 表示要写入多少行像素
     * 最大值为瓦片高度（通常 258）
     */
    public int wysize;

    /**
     * 计算写入区域的像素总数
     *
     * @return 像素总数
     */
    public long getPixelCount() {
        return (long) wxsize * wysize;
    }

    @Override
    public String toString() {
        return String.format("WriteInfo{start=(%d,%d), size=%dx%d}", wx, wy, wxsize, wysize);
    }
}
