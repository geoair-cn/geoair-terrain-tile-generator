import cn.geoair.map.dynamic.terrain.ttg.cesium.CesiumTerrainGenerator;
import cn.geoair.map.dynamic.terrain.ttg.cesium.model.CesiumOptions;

/**
 * @author ：张俊
 * @date ：Created in 2026/9/7 13:42
 * @description： TODO
 */
public class Dem2CesiumTest {
    public static void main(String[] args) throws Exception {
        CesiumOptions options = CesiumOptions.defaultCesiumOptions( 13, 4326, "UUID");
        CesiumTerrainGenerator.generate("E:\\gis测试数据\\测试数据\\tiff\\新疆地形\\新疆维吾尔自治区.tif", "E:\\gis测试数据\\测试数据\\三维地形\\xj0-13", options);
    }
}
