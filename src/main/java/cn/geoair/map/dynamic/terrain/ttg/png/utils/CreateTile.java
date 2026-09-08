package cn.geoair.map.dynamic.terrain.ttg.png.utils;

import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconst;

import java.io.File;
import java.lang.management.ManagementFactory;

public class CreateTile {

    // ThreadLocal：每线程打开一个独立 Dataset，避免 libtiff 多线程 Seek 冲突
    private static String dsPath = null;
    private static final ThreadLocal<Dataset> threadDataset = new ThreadLocal<>();

    // 静态默认填充数组 + ThreadLocal 编码缓冲区，消除每片分配 ~2MB 垃圾
    private static byte[] defaultFillR, defaultFillG, defaultFillB, defaultFillA;
    private static final ThreadLocal<ThreadBuffers> threadBuf = new ThreadLocal<>();

    private static Driver memDriver = null;
    private static Driver pngDriver = null;
    private static int outTileSize1;

    private static class ThreadBuffers {
        final int[] r = new int[258 * 258];
        final int[] g = new int[258 * 258];
        final int[] b = new int[258 * 258];
    }

    static { gdal.AllRegister(); }

    private static int currentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(name.split("@")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    public static class OverviewInfo {
        public int index = -1;
        public double startX, startY;
        public int width, height;
        public double resX, resY;
    }

    public static class ReadInfo {
        public int rx, ry, rxsize, rysize;
    }

    public static class WriteInfo {
        public int wx, wy, wxsize, wysize;
    }

    public static class CreateInfo {
        public int outTileSize;
        public OverviewInfo overviewInfo;
        public ReadInfo rb;
        public WriteInfo wb;
        public String encoding;
        public int x, y, z;
        public String outputTile;
    }

    public interface TileCallback {
        void onComplete(Exception err, int pid);
    }

    public static void initDsPath(String path) {
        dsPath = path;
    }

    public static void closeAll() {
        // 关闭主线程的 dataset
        Dataset mainDs = threadDataset.get();
        if (mainDs != null) {
            try { mainDs.delete(); } catch (Exception ex) {}
            threadDataset.remove();
        }
        dsPath = null;
    }

    private static Dataset getThreadDataset() {
        Dataset ds = threadDataset.get();
        if (ds == null) {
            ds = gdal.Open(dsPath);
            threadDataset.set(ds);
        }
        return ds;
    }

    public static void createTile(CreateInfo createInfo, TileCallback callback) {
        Dataset msmDS = null;

        try {
            final int ts = createInfo.outTileSize;
            outTileSize1 = ts;

            // ============ 1. 初始化（只做一次） ============
            if (memDriver == null) {
                memDriver = gdal.GetDriverByName("MEM");
                if (memDriver == null) {
                    if (callback != null) {
                        callback.onComplete(new RuntimeException("无法获取 MEM 驱动"),
                                currentPid());
                    }
                    return;
                }
            }

            // 延迟初始化静态默认填充数组（只分配一次）
            if (defaultFillR == null) {
                int total = ts * ts;
                defaultFillR = new byte[total];
                defaultFillG = new byte[total];
                defaultFillB = new byte[total];
                defaultFillA = new byte[total];
                for (int i = 0; i < total; i++) {
                    defaultFillR[i] = (byte) DemEncode.INVALID_COLOR[0];
                    defaultFillG[i] = (byte) DemEncode.INVALID_COLOR[1];
                    defaultFillB[i] = (byte) DemEncode.INVALID_COLOR[2];
                    defaultFillA[i] = (byte) 255;
                }
            }

            // ============ 2. 创建内存数据集 ============
            msmDS = memDriver.Create("", outTileSize1, outTileSize1, 4, gdalconst.GDT_Byte);
            if (msmDS == null) {
                if (callback != null) {
                    callback.onComplete(new RuntimeException("无法创建内存数据集"),
                            currentPid());
                }
                return;
            }

            // ============ 3. 生成瓦片（每线程打开独立 Dataset，避免 libtiff 并发 Seek 冲突） ============
            writeTerrainTile(getThreadDataset(), createInfo.overviewInfo, createInfo.rb, createInfo.wb,
                    createInfo.encoding, msmDS);

            // ============ 4. 保存为 PNG ============
            String pngPath = createInfo.outputTile + File.separator +
                             createInfo.z + File.separator + createInfo.x + File.separator + createInfo.y + ".png";
            File pngFile = new File(pngPath);
            pngFile.getParentFile().mkdirs();

            if (pngDriver == null) {
                pngDriver = gdal.GetDriverByName("PNG");
                if (pngDriver == null) {
                    if (callback != null) {
                        callback.onComplete(new RuntimeException("无法获取 PNG 驱动"),
                                currentPid());
                    }
                    return;
                }
            }

            Dataset copyDS = pngDriver.CreateCopy(pngPath, msmDS);
            if (copyDS == null) {
                if (callback != null) {
                    callback.onComplete(new RuntimeException("PNG 文件创建失败: " + pngPath),
                            currentPid());
                }
                return;
            }
            copyDS.delete();

            if (callback != null) {
                callback.onComplete(null, currentPid());
            }

        } catch (Exception e) {
            System.err.println("createTile 异常: " + e.getMessage());
            e.printStackTrace();
            if (callback != null) {
                callback.onComplete(e, currentPid());
            }
        } finally {
            if (msmDS != null) {
                try { msmDS.delete(); } catch (Exception ex) {}
            }
        }
    }

    private static void writeTerrainTile(Dataset ds, OverviewInfo overviewInfo, ReadInfo readInfo,
                                         WriteInfo writeInfo, String encoding, Dataset msmDS) {
        Band baseBand = ds.GetRasterBand(1);
        if (baseBand == null) throw new RuntimeException("无法获取基础波段 (band 1)");

        int overviewCount = baseBand.GetOverviewCount();
        Band readBand;
        if (overviewInfo.index == -1) {
            readBand = baseBand;
        } else if (overviewCount > 0 && overviewInfo.index < overviewCount) {
            readBand = baseBand.GetOverview(overviewInfo.index);
        } else if (overviewCount > 0) {
            readBand = baseBand.GetOverview(overviewCount - 1);
        } else {
            readBand = baseBand;
        }
        if (readBand == null) throw new RuntimeException("无法获取读取波段");

        int ovWidth = readBand.getXSize();
        int ovHeight = readBand.getYSize();
        int dataType = readBand.getDataType();
        Double noDataValue = getNoDataValue(readBand);

        if (readInfo.rx < 0 || readInfo.ry < 0 || readInfo.rx >= ovWidth || readInfo.ry >= ovHeight)
            throw new RuntimeException("读取起始位置超出范围");
        if (readInfo.rx + readInfo.rxsize > ovWidth || readInfo.ry + readInfo.rysize > ovHeight)
            throw new RuntimeException("读取范围超出边界");

        int pixelCount = writeInfo.wxsize * writeInfo.wysize;

        // ============ 复用 ThreadLocal 编码缓冲区（不再每片 new 三个 258×258 int[]） ============
        ThreadBuffers buf = threadBuf.get();
        if (buf == null) {
            buf = new ThreadBuffers();
            threadBuf.set(buf);
        }

        if (dataType == gdalconst.GDT_Int16) {
            short[] buffer = new short[pixelCount];
            readBand.ReadRaster(readInfo.rx, readInfo.ry, readInfo.rxsize, readInfo.rysize,
                    writeInfo.wxsize, writeInfo.wysize, dataType, buffer);
            for (int i = 0; i < pixelCount; i++) {
                double hv = buffer[i];
                int[] color = (noDataValue != null && hv == noDataValue) ? DemEncode.INVALID_COLOR : encode(hv, encoding);
                buf.r[i] = color[0]; buf.g[i] = color[1]; buf.b[i] = color[2];
            }
        } else if (dataType == gdalconst.GDT_Float32) {
            float[] buffer = new float[pixelCount];
            readBand.ReadRaster(readInfo.rx, readInfo.ry, readInfo.rxsize, readInfo.rysize,
                    writeInfo.wxsize, writeInfo.wysize, dataType, buffer);
            for (int i = 0; i < pixelCount; i++) {
                double hv = buffer[i];
                int[] color = (noDataValue != null && hv == noDataValue) ? DemEncode.INVALID_COLOR : encode(hv, encoding);
                buf.r[i] = color[0]; buf.g[i] = color[1]; buf.b[i] = color[2];
            }
        } else if (dataType == gdalconst.GDT_Float64) {
            double[] buffer = new double[pixelCount];
            readBand.ReadRaster(readInfo.rx, readInfo.ry, readInfo.rxsize, readInfo.rysize,
                    writeInfo.wxsize, writeInfo.wysize, dataType, buffer);
            for (int i = 0; i < pixelCount; i++) {
                double hv = buffer[i];
                int[] color = (noDataValue != null && hv == noDataValue) ? DemEncode.INVALID_COLOR : encode(hv, encoding);
                buf.r[i] = color[0]; buf.g[i] = color[1]; buf.b[i] = color[2];
            }
        } else if (dataType == gdalconst.GDT_Byte) {
            byte[] buffer = new byte[pixelCount];
            readBand.ReadRaster(readInfo.rx, readInfo.ry, readInfo.rxsize, readInfo.rysize,
                    writeInfo.wxsize, writeInfo.wysize, dataType, buffer);
            for (int i = 0; i < pixelCount; i++) {
                double hv = buffer[i] & 0xFF;
                int[] color = (noDataValue != null && hv == noDataValue) ? DemEncode.INVALID_COLOR : encode(hv, encoding);
                buf.r[i] = color[0]; buf.g[i] = color[1]; buf.b[i] = color[2];
            }
        } else if (dataType == gdalconst.GDT_Int32) {
            int[] buffer = new int[pixelCount];
            readBand.ReadRaster(readInfo.rx, readInfo.ry, readInfo.rxsize, readInfo.rysize,
                    writeInfo.wxsize, writeInfo.wysize, dataType, buffer);
            for (int i = 0; i < pixelCount; i++) {
                double hv = buffer[i];
                int[] color = (noDataValue != null && hv == noDataValue) ? DemEncode.INVALID_COLOR : encode(hv, encoding);
                buf.r[i] = color[0]; buf.g[i] = color[1]; buf.b[i] = color[2];
            }
        } else {
            throw new RuntimeException("不支持的数据类型: " + dataType);
        }

        // ============ 用静态默认填充（不再每片 new 4 个 66K byte[]） ============
        int totalSize = outTileSize1 * outTileSize1;
        msmDS.GetRasterBand(1).WriteRaster(0, 0, outTileSize1, outTileSize1, defaultFillR);
        msmDS.GetRasterBand(2).WriteRaster(0, 0, outTileSize1, outTileSize1, defaultFillG);
        msmDS.GetRasterBand(3).WriteRaster(0, 0, outTileSize1, outTileSize1, defaultFillB);
        msmDS.GetRasterBand(4).WriteRaster(0, 0, outTileSize1, outTileSize1, defaultFillA);

        // ============ 写入实际数据（toByteArray 仍是临时分配，但已经最小化） ============
        if (pixelCount > 0) {
            msmDS.GetRasterBand(1).WriteRaster(writeInfo.wx, writeInfo.wy, writeInfo.wxsize, writeInfo.wysize,
                    gdalconst.GDT_Byte, toByteArray(buf.r, pixelCount));
            msmDS.GetRasterBand(2).WriteRaster(writeInfo.wx, writeInfo.wy, writeInfo.wxsize, writeInfo.wysize,
                    gdalconst.GDT_Byte, toByteArray(buf.g, pixelCount));
            msmDS.GetRasterBand(3).WriteRaster(writeInfo.wx, writeInfo.wy, writeInfo.wxsize, writeInfo.wysize,
                    gdalconst.GDT_Byte, toByteArray(buf.b, pixelCount));
        }
    }

    private static int[] encode(double heightVal, String encoding) {
        if ("mapbox".equals(encoding)) {
            return DemEncode.mapboxEncode(heightVal);
        } else {
            return DemEncode.terrariumEncode(heightVal);
        }
    }

    private static byte[] toByteArray(int[] ints, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (ints[i] & 0xFF);
        }
        return bytes;
    }

    /**
     * 获取 NoData 值
     */
    private static Double getNoDataValue(Band band) {
        try {
            Double[] noDataArray = new Double[1];
            band.GetNoDataValue(noDataArray);
            if (noDataArray != null && !Double.isNaN(noDataArray[0])) {
                return noDataArray[0];
            }
        } catch (Exception ignored) {}
        return null;
    }
}
