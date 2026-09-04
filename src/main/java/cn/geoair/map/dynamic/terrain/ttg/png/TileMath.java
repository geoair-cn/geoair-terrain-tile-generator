package cn.geoair.map.dynamic.terrain.ttg.png;

import java.util.HashMap;
import java.util.Map;

/**
 * 瓦片坐标计算工具。
 * <p>提供 XYZ 瓦片地理边界计算、地理坐标反算瓦片行列号、TMS/XYZ Y 轴翻转等功能。
 * 支持 EPSG:3857 (Web Mercator)、4326 (WGS84)、4490 (CGCS2000) 坐标系。</p>
 */
public class TileMath {

    /**
     * 地理边界框。
     */
    public static class BBox {
        public double xmin, ymin, xmax, ymax;

        public BBox(double xmin, double ymin, double xmax, double ymax) {
            this.xmin = xmin;
            this.ymin = ymin;
            this.xmax = xmax;
            this.ymax = ymax;
        }
    }

    /**
     * 各 EPSG 编码对应的全球地理边界。
     * <ul>
     *   <li>3857/900913: Web Mercator 正方形边界</li>
     *   <li>4326/4490: 经纬度 2:1 边界</li>
     * </ul>
     */
    public static final Map<Integer, BBox> TILE_BOUND_MAP = new HashMap<>();

    static {
        TILE_BOUND_MAP.put(3857, new BBox(-20037508.342789244, -20037508.342789244, 20037508.342789244, 20037508.342789244));
        TILE_BOUND_MAP.put(900913, new BBox(-20037508.342789244, -20037508.342789244, 20037508.342789244, 20037508.342789244));
        TILE_BOUND_MAP.put(4326, new BBox(-180, -90, 180, 90));
        TILE_BOUND_MAP.put(4490, new BBox(-180, -90, 180, 90));
    }

    /**
     * 瓦片行列号。
     */
    public static class TileRC {
        /** 行号（Y 方向） */
        public int row;
        /** 列号（X 方向） */
        public int column;

        public TileRC(int row, int column) {
            this.row = row;
            this.column = column;
        }
    }

    /**
     * 根据 XYZ 计算瓦片的地理边界（带像素偏移缓冲）。
     *
     * @param z      缩放级别
     * @param x      瓦片列号
     * @param y      瓦片行号
     * @param offset 像素偏移量（用于扩展/收缩边界）
     * @param epsg   坐标系 EPSG 编码
     * @return [xmin, ymin, xmax, ymax]
     */
    public static double[] tileEnvelope(int z, int x, int y, int offset, int epsg) {
        BBox bbox = TILE_BOUND_MAP.get(epsg);
        if (bbox == null) bbox = TILE_BOUND_MAP.get(3857);
        return tileEnvelope(z, x, y, offset, bbox, epsg);
    }

    /**
     * 根据 XYZ 计算瓦片的地理边界（指定边界框）。
     *
     * @param z      缩放级别
     * @param x      瓦片列号
     * @param y      瓦片行号
     * @param offset 像素偏移量
     * @param bbox   全球地理边界
     * @param epsg   坐标系 EPSG 编码
     * @return [xmin, ymin, xmax, ymax]
     */
    public static double[] tileEnvelope(int z, int x, int y, int offset, BBox bbox, int epsg) {
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
     * 根据地理坐标计算其在指定缩放级别下对应的瓦片行列号。
     *
     * @param coor 地理坐标 [x, y]
     * @param zoom 缩放级别
     * @param epsg 坐标系 EPSG 编码
     * @return 瓦片行列号
     */
    public static TileRC tileByCoordinate(double[] coor, int zoom, int epsg) {
        BBox bbox = TILE_BOUND_MAP.get(epsg);
        if (bbox == null) bbox = TILE_BOUND_MAP.get(3857);
        return tileByCoordinate(coor, zoom, bbox, epsg);
    }

    /**
     * 根据地理坐标计算其在指定缩放级别下对应的瓦片行列号（指定边界框）。
     *
     * @param coor 地理坐标 [x, y]
     * @param zoom 缩放级别
     * @param bbox 全球地理边界
     * @param epsg 坐标系 EPSG 编码
     * @return 瓦片行列号
     */
    public static TileRC tileByCoordinate(double[] coor, int zoom, BBox bbox, int epsg) {
        double left = bbox.xmin;
        double top = bbox.ymax;

        double width = coor[0] - left;
        double height = top - coor[1];

        int worldTileSize = 1 << zoom;
        double boundsWidth = bbox.xmax - bbox.xmin;
        double boundsHeight = bbox.ymax - bbox.ymin;

        double tileGeoSize = Math.max(boundsWidth, boundsHeight) * 1.0 / worldTileSize;
        double row = Math.floor(height / tileGeoSize);
        double column = Math.floor(width / tileGeoSize);

        row = Math.max(0, Math.min(row, worldTileSize - 1));
        column = Math.max(0, Math.min(column, worldTileSize - 1));

        return new TileRC((int) row, (int) column);
    }

    /**
     * TMS 行号转 XYZ 行号（Y 轴翻转）。
     *
     * @param tmsY TMS 行号
     * @param zoom 缩放级别
     * @return XYZ 行号
     */
    public static int tmsToXyzY(int tmsY, int zoom) {
        return (1 << zoom) - 1 - tmsY;
    }

    /**
     * XYZ 行号转 TMS 行号（Y 轴翻转）。
     *
     * @param xyzY XYZ 行号
     * @param zoom 缩放级别
     * @return TMS 行号
     */
    public static int xyzToTmsY(int xyzY, int zoom) {
        return (1 << zoom) - 1 - xyzY;
    }
}
