package cn.geoair.map.dynamic.terrain.ttg.model;

import org.gdal.gdal.Dataset;

/**
 * 数据集信息
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

    public double west() { return west; }
    public double south() { return south; }
    public double east() { return east; }
    public double north() { return north; }
    public double originX() { return originX; }
    public double originY() { return originY; }
    public double resolutionX() { return resolutionX; }
    public double resolutionY() { return resolutionY; }
    public int width() { return width; }
    public int height() { return height; }

    public static DatasetInfo from(Dataset dataset) {
        double[] transform = dataset.GetGeoTransform();
        if (transform[2] != 0 || transform[4] != 0 || transform[1] <= 0 || transform[5] >= 0) {
            throw new IllegalArgumentException("Cesium 地形仅支持北向上、无旋转的 DEM");
        }
        double east = transform[0] + transform[1] * dataset.GetRasterXSize();
        double south = transform[3] + transform[5] * dataset.GetRasterYSize();
        return new DatasetInfo(transform[0], south, east, transform[3], transform[0], transform[3],
                transform[1], transform[5], dataset.GetRasterXSize(), dataset.GetRasterYSize());
    }
}
