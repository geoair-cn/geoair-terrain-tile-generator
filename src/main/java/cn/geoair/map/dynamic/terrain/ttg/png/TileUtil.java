package cn.geoair.map.dynamic.terrain.ttg.png;

import java.util.HashMap;
import java.util.Map;

public class TileUtil {

    public static class BBox {
        public double xmin, ymin, xmax, ymax;

        public BBox(double xmin, double ymin, double xmax, double ymax) {
            this.xmin = xmin;
            this.ymin = ymin;
            this.xmax = xmax;
            this.ymax = ymax;
        }
    }

    public static final Map<Integer, BBox> TILE_BOUND_MAP = new HashMap<>();

    static {
        // Web Mercator (3857/900913) - 正方形
        TILE_BOUND_MAP.put(3857, new BBox(-20037508.342789244, -20037508.342789244, 20037508.342789244, 20037508.342789244));
        TILE_BOUND_MAP.put(900913, new BBox(-20037508.342789244, -20037508.342789244, 20037508.342789244, 20037508.342789244));
        // WGS84 (4326) - 经纬度，宽高比 2:1
        TILE_BOUND_MAP.put(4326, new BBox(-180, -90, 180, 90));
        // CGCS2000 (4490) - 经纬度，宽高比 2:1
        TILE_BOUND_MAP.put(4490, new BBox(-180, -90, 180, 90));
    }

    public static class TileRC {
        public int row, column;

        public TileRC(int row, int column) {
            this.row = row;
            this.column = column;
        }
    }

    /**
     * 根据 XYZ 计算对应地理坐标系的地理边界
     * 返回: [xmin, ymin, xmax, ymax]
     */
    public static double[] stTileEnvelope(int z, int x, int y, int offset, int epsg) {
        BBox bbox = TILE_BOUND_MAP.get(epsg);
        if (bbox == null) bbox = TILE_BOUND_MAP.get(3857);
        return stTileEnvelope(z, x, y, offset, bbox, epsg);
    }

    public static double[] stTileEnvelope(int z, int x, int y, int offset, BBox bbox, int epsg) {
        final double tileSize = 256.0;
        double boundsWidth = bbox.xmax - bbox.xmin;
        double boundsHeight = bbox.ymax - bbox.ymin;

        if (boundsWidth <= 0 || boundsHeight <= 0) {
            throw new IllegalArgumentException("Geometric bounds are too small");
        }
        if (z < 0 || z >= 32) {
            throw new IllegalArgumentException("Invalid tile zoom value: " + z);
        }

        int worldTileSize = 1 << z;
        if (x < 0 || x >= worldTileSize) {
            throw new IllegalArgumentException("Invalid tile x value: " + x);
        }
        if (y < 0 || y >= worldTileSize) {
            throw new IllegalArgumentException("Invalid tile y value: " + y);
        }

        // ============ 与Node版本保持完全一致 ============
        // 地理切片分辨率
        double tileGeoSizeX = boundsWidth * 1.0 / worldTileSize;
        double tileGeoSizeY = boundsHeight * 1.0 / worldTileSize;
        double tileGeoSize = Math.max(tileGeoSizeX, tileGeoSizeY);

        double x1 = bbox.xmin + tileGeoSize * x - tileGeoSize / tileSize * offset;
        double x2 = bbox.xmin + tileGeoSize * (x + 1) + tileGeoSize / tileSize * offset;

        double y1 = bbox.ymax - tileGeoSize * (y + 1) - tileGeoSize / tileSize * offset;
        double y2 = bbox.ymax - tileGeoSize * (y) + tileGeoSize / tileSize * offset;

        return new double[]{x1, y2, x2, y1};
    }

    /**
     * 根据任意地理坐标计算在指定 zoom 层级下其对应的瓦片行列号
     */
    public static TileRC getTileByCoors(double[] coor, int zoom, int epsg) {
        BBox bbox = TILE_BOUND_MAP.get(epsg);
        if (bbox == null) bbox = TILE_BOUND_MAP.get(3857);
        return getTileByCoors(coor, zoom, bbox, epsg);
    }

    public static TileRC getTileByCoors(double[] coor, int zoom, BBox bbox, int epsg) {
        double left = bbox.xmin;
        double top = bbox.ymax;

        double width = coor[0] - left;
        double height = top - coor[1];

        int worldTileSize = 1 << zoom;
        double boundsWidth = bbox.xmax - bbox.xmin;
        double boundsHeight = bbox.ymax - bbox.ymin;

        // ============ 与Node版本保持完全一致 ============
        double tileGeoSize = Math.max(boundsWidth, boundsHeight) * 1.0 / worldTileSize;
        double row = Math.floor(height / tileGeoSize);
        double column = Math.floor(width / tileGeoSize);

        row = Math.max(0, Math.min(row, worldTileSize - 1));
        column = Math.max(0, Math.min(column, worldTileSize - 1));

        return new TileRC((int)row, (int)column);
    }

    /**
     * TMS 转 XYZ (Y 轴翻转)
     */
    public static int tmsToXyzY(int ty, int tz) {
        return (1 << tz) - 1 - ty;
    }

    /**
     * XYZ 转 TMS (Y 轴翻转)
     */
    public static int xyzToTmsY(int ty, int tz) {
        return (1 << tz) - 1 - ty;
    }
}
