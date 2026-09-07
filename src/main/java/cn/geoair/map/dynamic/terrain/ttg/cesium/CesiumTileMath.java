package cn.geoair.map.dynamic.terrain.ttg.cesium;

import cn.geoair.map.dynamic.terrain.ttg.model.Bounds;
import cn.geoair.map.dynamic.terrain.ttg.model.Range;

final class CesiumTileMath {
    private CesiumTileMath() {
    }

    static Bounds bounds(int zoom, int x, int tmsY) {
        int xTiles = 1 << (zoom + 1);
        int yTiles = 1 << zoom;
        if (x < 0 || x >= xTiles || tmsY < 0 || tmsY >= yTiles) {
            throw new IllegalArgumentException("Invalid Cesium TMS tile coordinate");
        }
        double width = 360.0 / xTiles;
        double height = 180.0 / yTiles;
        double west = -180.0 + x * width;
        double east = west + width;
        double south = -90.0 + tmsY * height;
        return new Bounds(west, south, east, south + height);
    }

    static Range range(double west, double south, double east, double north, int zoom) {
        int xTiles = 1 << (zoom + 1);
        int yTiles = 1 << zoom;
        int minX = clamp((int) Math.floor((west + 180.0) / 360.0 * xTiles), 0, xTiles - 1);
        int maxX = clamp((int) Math.floor((east + 180.0) / 360.0 * xTiles), 0, xTiles - 1);
        int minTmsY = clamp((int) Math.floor((south + 90.0) / 180.0 * yTiles), 0, yTiles - 1);
        int maxTmsY = clamp((int) Math.floor((north + 90.0) / 180.0 * yTiles), 0, yTiles - 1);
        return new Range(Math.min(minX, maxX), Math.min(minTmsY, maxTmsY),
                Math.max(minX, maxX), Math.max(minTmsY, maxTmsY));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
