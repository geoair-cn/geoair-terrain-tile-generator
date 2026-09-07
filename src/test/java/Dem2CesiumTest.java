import cn.geoair.map.dynamic.terrain.ttg.cesium.CesiumTerrainGenerator;
import cn.geoair.map.dynamic.terrain.ttg.cesium.model.CesiumOptions;

/**
 * @author ：张俊
 * @date ：Created in 2026/9/7 13:42
 * @description： TODO
 */
public class Dem2CesiumTest {
    public static void main(String[] args) throws Exception {
        CesiumOptions options = CesiumOptions.defaultCesiumOptions( 14, 4326, "UUID");
        CesiumTerrainGenerator.generate("E:\\gis测试数据\\测试数据\\tiff\\新疆地形\\新疆维吾尔自治区.tif", "G:\\softdir\\nginx-1.18.0\\nginx_pxy\\outcesium", options);
    }
}
