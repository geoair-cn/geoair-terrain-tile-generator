package cn.geoair.map.dynamic.terrain.ttg.model;

/**
 * 瓦片行列号范围
 */
public class Range {
    private final int minX;
    private final int minY;
    private final int maxX;
    private final int maxY;

    public Range(int minX, int minY, int maxX, int maxY) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
}
