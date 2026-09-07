package cn.geoair.map.dynamic.terrain.ttg.model;

/**
 * 瓦片地理边界范围
 */
public class Bounds {
    private final double west;
    private final double south;
    private final double east;
    private final double north;

    public Bounds(double west, double south, double east, double north) {
        this.west = west;
        this.south = south;
        this.east = east;
        this.north = north;
    }

    public double west() {
        return west;
    }

    public double south() {
        return south;
    }

    public double east() {
        return east;
    }

    public double north() {
        return north;
    }

    public double longitudeAt(int column, int size) {
        return west + column * (east - west) / (size - 1.0);
    }

    public double latitudeAtNorthRow(int row, int size) {
        return north - row * (north - south) / (size - 1.0);
    }
}
