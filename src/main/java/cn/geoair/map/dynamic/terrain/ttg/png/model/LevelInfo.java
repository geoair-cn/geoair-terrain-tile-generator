package cn.geoair.map.dynamic.terrain.ttg.png.model;

/**
 * 瓦片级别信息
 * <p>
 * 存储某个缩放级别下需要生成的瓦片坐标范围。
 * 使用 TMS (Tile Map Service) 坐标系：
 * - X 轴：列方向，从西向东递增
 * - Y 轴：行方向，从北向南递增（注意：与经纬度的南北方向相反）
 * <p>
 * 示例（zoom=10 时）：
 * - tminx=512, tmaxx=520: X 方向需要生成第 512 到 520 列的瓦片
 * - tminy=256, tmaxy=264: Y 方向需要生成第 256 到 264 行的瓦片
 * - 总瓦片数 = (tmaxx - tminx + 1) * (tmaxy - tminy + 1)
 */
public class LevelInfo {
    /**
     * 最小 X 坐标（最西边的瓦片列号）
     */
    public int tminx;

    /**
     * 最小 Y 坐标（最北边的瓦片行号）
     * <p>
     * 注意：在 TMS 坐标系中，Y 轴从北向南递增，
     * 所以 tminy 实际上是北边界对应的行号
     */
    public int tminy;

    /**
     * 最大 X 坐标（最东边的瓦片列号）
     */
    public int tmaxx;

    /**
     * 最大 Y 坐标（最南边的瓦片行号）
     * <p>
     * 注意：在 TMS 坐标系中，Y 轴从北向南递增，
     * 所以 tmaxy 实际上是南边界对应的行号
     */
    public int tmaxy;

    /**
     * 计算该级别下的总瓦片数
     *
     * @return 总瓦片数量
     */
    public long getTileCount() {
        return (long) (tmaxx - tminx + 1) * (tmaxy - tminy + 1);
    }

    @Override
    public String toString() {
        return String.format("LevelInfo{X=[%d, %d], Y=[%d, %d], 瓦片数=%d}",
                tminx, tmaxx, tminy, tmaxy, getTileCount());
    }
}
