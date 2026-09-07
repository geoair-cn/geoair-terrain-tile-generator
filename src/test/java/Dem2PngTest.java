import cn.geoair.map.dynamic.terrain.ttg.png.PngTerrainTileGenerator;

/**
 * @author ：张俊
 * @date ：Created in 2026/9/7 13:43
 * @description： TODO
 */
public class Dem2PngTest {
    public static void main(String[] args) throws Exception {
        PngTerrainTileGenerator.Options options = PngTerrainTileGenerator.Options.defaultMapBoxOptions(
                1, 10, 4326, "UUID");
        PngTerrainTileGenerator.generate("E:\\gis测试数据\\测试数据\\tiff\\新疆地形\\新疆维吾尔自治区.tif", "E:\\gis测试数据\\测试数据\\tiff\\新疆地形\\outjava", options);
    }
}
