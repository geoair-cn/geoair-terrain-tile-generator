package cn.geoair.map.dynamic.terrain.ttg.cesium;

import cn.geoair.map.dynamic.terrain.ttg.cesium.model.CesiumOptions;

/**
 * DEM 转 Cesium quantized-mesh 地形瓦片生成器。
 *
 * <h3>命令行参数（按顺序）</h3>
 * <ol>
 *   <li>input  — 输入 DEM TIFF 文件路径</li>
 *   <li>output — 输出目录路径</li>
 *   <li>minZoom — 最小缩放级别（默认 0）</li>
 *   <li>maxZoom — 最大缩放级别（默认 10）</li>
 *   <li>epsg   — 目标坐标系，固定 4326</li>
 *   <li>isClean — 是否清空输出目录：1=清空, 0=保留（默认 1）</li>
 *   <li>resampling — 重采样方法（默认 2=bilinear）</li>
 *   <li>reProjectFileName — 重投影文件名，UUID 表示自动生成（默认 UUID）</li>
 *   <li>meshPrecision — 网格精度：LOW/MEDIUM/HIGH/ULTRA（默认 MEDIUM）</li>
 *   <li>gzip — 是否 gzip 压缩瓦片：true/false 或 1/0（默认 false）</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>
 *   Dem2Cesium input.tif output 0 12 4326 1 2 dem_4326 HIGH false
 * </pre>
 *
 * @author 张俊
 * @date Created in 2026/8/5 14:06
 */
public class Dem2Cesium {
    public static void main(String[] args) throws Exception {
        String input = args.length > 0 ? args[0] : "";
        String output = args.length > 1 ? args[1] : "";
        if (input.isEmpty() || output.isEmpty()) {
            System.out.println("Usage: Dem2Cesium <input.tif> <output> [minZoom] [maxZoom] [epsg] [isClean] [resampling] [reProjectFileName] [meshPrecision] [gzip]");
            return;
        }
        int minZoom = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int maxZoom = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        int epsg = args.length > 4 ? Integer.parseInt(args[4]) : 4326;
        int isClean = args.length > 5 ? Integer.parseInt(args[5]) : 1;
        int resampling = args.length > 6 ? Integer.parseInt(args[6]) : 2;
        String reProjectFileName = args.length > 7 ? args[7] : "UUID";
        CesiumTerrainGenerator.MeshPrecision precision = args.length > 8
                ? CesiumTerrainGenerator.MeshPrecision.valueOf(args[8].toUpperCase())
                : CesiumTerrainGenerator.MeshPrecision.MEDIUM;
        boolean gzip = args.length > 9 && ("true".equalsIgnoreCase(args[9]) || "1".equals(args[9]));
        CesiumOptions options = new CesiumOptions(minZoom, maxZoom, epsg, isClean == 1,
                resampling, reProjectFileName, precision, gzip);
        CesiumTerrainGenerator.generate(input, output, options);
    }
}
