package cn.geoair.map.dynamic.terrain.ttg.png;

import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconst;
import org.gdal.osr.SpatialReference;

/**
 * GDAL 操作辅助类。
 * <p>封装 GDAL 驱动查找、重采样方法映射、影像重投影等常用操作。</p>
 */
public class GdalHelper {

    static {
        gdal.AllRegister();
    }

    /**
     * 根据驱动名称查找 GDAL 驱动。
     *
     * @param driverName 驱动名称（如 "PNG"、"MEM"、"GTiff"）
     * @return GDAL Driver 对象
     * @throws RuntimeException 驱动不存在时抛出
     */
    public static Driver getDriverByName(String driverName) {
        driverName = driverName.toUpperCase();
        int count = gdal.GetDriverCount();
        for (int i = 0; i < count; i++) {
            Driver driver = gdal.GetDriver(i);
            if (driver.getShortName().equals(driverName)) {
                return driver;
            }
        }
        throw new RuntimeException("GDAL 中不存在驱动: " + driverName);
    }

    /**
     * 将重采样方式编号转换为 GDAL 常量。
     * <ul>
     *   <li>1 = AVERAGE（加权平均）</li>
     *   <li>2 = BILINEAR（双线性内插）</li>
     *   <li>3 = CUBIC（三次卷积）</li>
     *   <li>4 = CUBICSPLINE（B样条卷积）</li>
     *   <li>5 = LANCZOS（Lanczos窗口sinc卷积）</li>
     *   <li>6 = MODE（众数）</li>
     *   <li>7 = NEAREST（最近邻）</li>
     * </ul>
     *
     * @param resampling 重采样方式编号（1-7）
     * @return GDAL gdalconst 重采样常量
     */
    public static int getResampling(int resampling) {
        switch (resampling) {
            case 1: return gdalconst.GRA_Average;
            case 2: return gdalconst.GRA_Bilinear;
            case 3: return gdalconst.GRA_Cubic;
            case 4: return gdalconst.GRA_CubicSpline;
            case 5: return gdalconst.GRA_Lanczos;
            case 6: return gdalconst.GRA_Mode;
            case 7: return gdalconst.GRA_NearestNeighbour;
            default: return gdalconst.GRA_Cubic;
        }
    }

    /**
     * 将重采样方式编号转换为 BuildOverviews 所需的字符串名称。
     *
     * @param resampling 重采样方式编号（1-7）
     * @return 重采样方法名称字符串（如 "BILINEAR"）
     */
    public static String getOverviewResamplingName(int resampling) {
        switch (resampling) {
            case 1: return "AVERAGE";
            case 2: return "BILINEAR";
            case 3: return "CUBIC";
            case 4: return "CUBICSPLINE";
            case 5: return "LANCZOS";
            case 6: return "MODE";
            case 7: return "NEAREST";
            default: return "CUBIC";
        }
    }

    /**
     * 重投影影像文件到指定坐标系。
     * <p>内部使用 AutoCreateWarpedVRT 创建虚拟重投影，再 CreateCopy 写出实体文件，
     * 并自动传递 NoData 值。</p>
     *
     * @param srcPath    源文件路径
     * @param dstPath    目标文件路径
     * @param targetEpsg 目标坐标系 EPSG 编码
     * @param resampling 重采样方式编号（1-7）
     */
    public static void reprojectImage(String srcPath, String dstPath, int targetEpsg, int resampling) {
        Dataset srcDs = gdal.Open(srcPath);
        try {
            reprojectImage(srcDs, dstPath, targetEpsg, resampling);
        } finally {
            srcDs.delete();
        }
    }

    /**
     * 重投影影像 Dataset 到指定坐标系。
     *
     * @param srcDs      源 GDAL Dataset
     * @param dstPath    目标文件路径
     * @param targetEpsg 目标坐标系 EPSG 编码
     * @param resampling 重采样方式编号（1-7）
     */
    public static void reprojectImage(Dataset srcDs, String dstPath, int targetEpsg, int resampling) {
        SpatialReference srcSrs = srcDs.GetProjectionRef() != null ?
                new SpatialReference(srcDs.GetProjectionRef()) : null;
        SpatialReference tSrs = new SpatialReference();
        tSrs.ImportFromEPSG(targetEpsg);

        Dataset vrtDs = gdal.AutoCreateWarpedVRT(srcDs,
                srcSrs != null ? srcSrs.ExportToWkt() : null,
                tSrs.ExportToWkt(), getResampling(resampling));

        if (vrtDs == null) {
            throw new RuntimeException("无法创建 Warped VRT，重投影失败");
        }

        Driver driver = srcDs.GetDriver();
        Dataset dstDs = driver.CreateCopy(dstPath, vrtDs);
        vrtDs.delete();

        if (dstDs == null) {
            throw new RuntimeException("无法创建重投影输出文件: " + dstPath);
        }

        // 传递 NoData 值
        Band srcBand = srcDs.GetRasterBand(1);
        Double[] noDataValue = new Double[1];
        srcBand.GetNoDataValue(noDataValue);
        if (!Double.isNaN(noDataValue[0])) {
            dstDs.GetRasterBand(1).SetNoDataValue(noDataValue[0]);
        }

        dstDs.delete();
    }
}
