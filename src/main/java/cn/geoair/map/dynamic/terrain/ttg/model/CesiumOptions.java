package cn.geoair.map.dynamic.terrain.ttg.model;

import cn.geoair.map.dynamic.terrain.ttg.cesium.CesiumTerrainGenerator.MeshPrecision;

/**
 * Cesium 地形生成选项
 */
public class CesiumOptions {
    private final int minZoom;
    private final int maxZoom;
    private final int targetEpsg;
    private final boolean cleanOutput;
    private final int resampling;
    private final String reprojectFileName;
    private final MeshPrecision precision;

    public CesiumOptions(int minZoom, int maxZoom, int targetEpsg, boolean cleanOutput,
                         int resampling, String reprojectFileName, MeshPrecision precision) {
        if (minZoom < 0 || maxZoom < minZoom || maxZoom > 30) {
            throw new IllegalArgumentException("缩放级别必须满足 0 <= minZoom <= maxZoom <= 30");
        }
        if (targetEpsg != 4326) {
            throw new IllegalArgumentException("Cesium quantized-mesh 当前仅支持 EPSG:4326 输出");
        }
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.targetEpsg = targetEpsg;
        this.cleanOutput = cleanOutput;
        this.resampling = resampling;
        this.reprojectFileName = reprojectFileName;
        this.precision = precision == null ? MeshPrecision.MEDIUM : precision;
    }

    public int minZoom() { return minZoom; }
    public int maxZoom() { return maxZoom; }
    public int targetEpsg() { return targetEpsg; }
    public boolean cleanOutput() { return cleanOutput; }
    public int resampling() { return resampling; }
    public String reprojectFileName() { return reprojectFileName; }
    public MeshPrecision precision() { return precision; }
}
