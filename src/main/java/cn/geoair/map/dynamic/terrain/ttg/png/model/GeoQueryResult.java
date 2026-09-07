package cn.geoair.map.dynamic.terrain.ttg.png.model;

/**
 * 地理查询结果
 * <p>
 * 包含从 DEM 中读取瓦片数据时所需的读取区域和写入区域信息。
 * <p>
 * 在瓦片生成过程中，需要计算：
 * 1. 从 DEM 中读取哪个区域（ReadInfo）
 * 2. 写入到瓦片的哪个位置（WriteInfo）
 * <p>
 * 这是因为瓦片可能只部分覆盖 DEM，或者超出 DEM 范围，
 * 需要进行裁剪和偏移计算。
 * <p>
 * 示例：
 * <pre>
 * DEM 范围: [73.5, 34.3, 96.4, 49.2]
 * 瓦片范围: [87.0, 43.0, 90.0, 46.0] (完全在 DEM 内)
 *
 * 结果:
 * - ReadInfo: 从 DEM 像素 (54000, 25000) 开始读取 12000x12000 像素
 * - WriteInfo: 写入到瓦片的 (1, 1) 位置，大小 256x256
 * </pre>
 */
public class GeoQueryResult {
    /**
     * 读取区域信息
     * <p>
     * 描述从 DEM 中读取像素的区域
     */
    public ReadInfo rb;

    /**
     * 写入区域信息
     * <p>
     * 描述写入到瓦片图像的区域
     */
    public WriteInfo wb;

    @Override
    public String toString() {
        return String.format("GeoQueryResult{read=%s, write=%s}", rb, wb);
    }
}
