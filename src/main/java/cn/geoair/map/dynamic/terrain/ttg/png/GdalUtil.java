package cn.geoair.map.dynamic.terrain.ttg.png;

import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconst;
import org.gdal.osr.SpatialReference;

public class GdalUtil {

    static {
        gdal.AllRegister();
    }

    public static Driver getDriverByName(String driverName) {
        driverName = driverName.toUpperCase();
        int count = gdal.GetDriverCount();
        for (int i = 0; i < count; i++) {
            Driver driver = gdal.GetDriver(i);
            if (driver.getShortName().equals(driverName)) {
                return driver;
            }
        }
        throw new RuntimeException("当前gdal中不存在输入的驱动名称: " + driverName);
    }

    /**
     * 获取GDAL重采样方法
     *
     * @param resampling 1-7
     * @return GDAL重采样常量
     */
    public static int getResampling(int resampling) {
        switch (resampling) {
            case 1:
                return gdalconst.GRA_Average;        // 加权平均法
            case 2:
                return gdalconst.GRA_Bilinear;       // 双线性内插法
            case 3:
                return gdalconst.GRA_Cubic;          // 三次卷积内插法
            case 4:
                return gdalconst.GRA_CubicSpline;    // B样条卷积内插法
            case 5:
                return gdalconst.GRA_Lanczos;        // Lanczos窗口sinc卷积内插法
            case 6:
                return gdalconst.GRA_Mode;           // 最常出现值法
            case 7:
                return gdalconst.GRA_NearestNeighbour; // 最邻近法 (注意: 是NearestNeighbour不是NearestNeighbor)
            default:
                return gdalconst.GRA_Cubic;
        }
    }

    /**
     * 获取BuildOverviews的重采样方法名称（字符串形式）
     */
    public static String getBuildOverviewResampling(int resampling) {
        switch (resampling) {
            case 1:
                return "AVERAGE";
            case 2:
                return "BILINEAR";
            case 3:
                return "CUBIC";
            case 4:
                return "CUBICSPLINE";
            case 5:
                return "LANCZOS";
            case 6:
                return "MODE";
            case 7:
                return "NEAREST";
            default:
                return "CUBIC";
        }
    }

    public static void reprojectImage(String srcPath, String dstPath, int tEpsg, int resampling) {
        Dataset srcDs = gdal.Open(srcPath);
        try {
            reprojectImage(srcDs, dstPath, tEpsg, resampling);
        } finally {
            srcDs.delete();
        }
    }

    public static void reprojectImage(Dataset srcDs, String dstPath, int tEpsg, int resampling) {
        SpatialReference srcSrs = srcDs.GetProjectionRef() != null ?
                new SpatialReference(srcDs.GetProjectionRef()) : null;
        SpatialReference tSrs = new SpatialReference();
        tSrs.ImportFromEPSG(tEpsg);

        // 使用 gdal.AutoCreateWarpedVRT 创建虚拟重投影，再用 CreateCopy 写出实体文件
        // VRT 内部自动计算输出范围、分辨率，不需要手工变换角点
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

        // 传递 NoData
        Band srcBand = srcDs.GetRasterBand(1);
        Double[] noDataValue = new Double[1];
        srcBand.GetNoDataValue(noDataValue);
        if (!Double.isNaN(noDataValue[0])) {
            dstDs.GetRasterBand(1).SetNoDataValue(noDataValue[0]);
        }

        dstDs.delete();
    }
}

