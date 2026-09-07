package cn.geoair.map.dynamic.terrain.ttg.png;

import cn.geoair.base.Gir;
import cn.geoair.map.dynamic.terrain.ttg.png.model.Options;

/**
 * DEM 转 PNG 地形瓦片生成器。
 *
 * <h3>命令行参数（按顺序）</h3>
 * <ol>
 *   <li>input  — 输入 DEM TIFF 文件路径</li>
 *   <li>output — 输出目录路径</li>
 *   <li>minZoom — 最小缩放级别（默认 0）</li>
 *   <li>maxZoom — 最大缩放级别（默认 15）</li>
 *   <li>epsg   — 目标坐标系 EPSG 编码（默认 4326）</li>
 *   <li>encoding — 编码方式：mapbox, terrarium（默认 mapbox）</li>
 *   <li>isClean — 是否清空输出目录：1=清空, 0=保留（默认 1）</li>
 *   <li>resampling — 重采样方法 0-7（默认 1=bilinear）</li>
 *   <li>reProjectFileName — 重投影文件名，UUID 表示自动生成（默认 UUID）</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>
 *   Dem2Png input.tif output 0 15 4326 mapbox 1 1 dem_4326
 * </pre>
 *
 * @author 张俊
 * @date Created in 2026/8/5 14:06
 */
public class Dem2Png {
    public static void main(String[] args) throws Exception {
        String input = args.length > 0 ? args[0] : "";
        String output = args.length > 1 ? args[1] : "";
        if (input.isEmpty() || output.isEmpty()) {
            System.out.println("Usage: Dem2Png <input.tif> <output> [minZoom] [maxZoom] [epsg] [encoding] [isClean] [resampling] [reProjectFileName]");
            return;
        }
        int minZoom = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int maxZoom = args.length > 3 ? Integer.parseInt(args[3]) : 12;
        int epsg = args.length > 4 ? Integer.parseInt(args[4]) : 4326;
        String encoding = args.length > 5 ? args[5] : "mapbox";
        int isClean = args.length > 6 ? Integer.parseInt(args[6]) : 1;
        int resampling = args.length > 7 ? Integer.parseInt(args[7]) : 1;
        String reProjectFileName = args.length > 8 ? args[8] : "UUID";

        Gir.log.info("Input: " + input);
        Gir.log.info("Output: " + output);
        Gir.log.info("Zoom: " + minZoom + " - " + maxZoom);
        Gir.log.info("EPSG: " + epsg);
        Gir.log.info("Encoding: " + encoding);
        Gir.log.info("ReProjectFileName: " + reProjectFileName);
        Gir.log.info("----------------------------");

        Options options = new Options(
                minZoom, maxZoom, epsg, encoding, isClean, resampling, reProjectFileName);
        PngTerrainTileGenerator.generate(input, output, options);
    }
}
