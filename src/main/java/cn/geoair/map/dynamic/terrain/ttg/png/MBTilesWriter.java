package cn.geoair.map.dynamic.terrain.ttg.png;

import cn.hutool.core.io.IoUtil;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;

/**
 * MBTiles 文件写入器。
 * <p>基于 SQLite 实现 MBTiles 1.0 规范的瓦片写入，
 * 支持批量事务提交以提升写入性能。</p>
 *
 * <h3>使用示例</h3>
 * <pre>
 *   try (MBTilesWriter writer = MBTilesWriter.open("output.mbtiles", "rwc")) {
 *       writer.startWriting();
 *       writer.putTile(z, x, y, tileFilePath);
 *       writer.stopWriting();
 *   }
 * </pre>
 */
public class MBTilesWriter implements Closeable {

    private Connection connection;
    private String path;

    /**
     * 打开或创建 MBTiles 文件。
     *
     * @param mbpath MBTiles 文件路径
     * @param mode   SQLite 打开模式："rwc"=读写创建，"rw"=读写，"r"=只读
     * @return MBTilesWriter 实例
     * @throws SQLException 数据库连接失败时抛出
     */
    public static MBTilesWriter open(String mbpath, String mode) throws SQLException {
        MBTilesWriter writer = new MBTilesWriter();
        writer.path = mbpath;
        String url = "jdbc:sqlite:" + mbpath;
        if ("rw".equals(mode) || "rwc".equals(mode)) {
            File file = new File(mbpath);
            if (!file.exists()) {
                writer.connection = DriverManager.getConnection(url);
                writer.initDatabase();
            } else {
                writer.connection = DriverManager.getConnection(url);
            }
        } else {
            writer.connection = DriverManager.getConnection(url);
        }
        return writer;
    }

    /**
     * 初始化 MBTiles 数据库表结构。
     */
    private void initDatabase() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS tiles (" +
                         "zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, " +
                         "PRIMARY KEY (zoom_level, tile_column, tile_row))");
            stmt.execute("CREATE TABLE IF NOT EXISTS metadata (" +
                         "name TEXT, value TEXT, PRIMARY KEY (name))");
        }
    }

    /**
     * 开始批量写入（关闭自动提交）。
     *
     * @throws SQLException 数据库错误时抛出
     */
    public void startWriting() throws SQLException {
        connection.setAutoCommit(false);
    }

    /**
     * 结束批量写入（提交事务并恢复自动提交）。
     *
     * @throws SQLException 数据库错误时抛出
     */
    public void stopWriting() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    /**
     * 将瓦片文件写入 MBTiles（读取文件后删除源文件）。
     *
     * @param z         缩放级别
     * @param x         瓦片列号
     * @param y         瓦片行号（TMS 坐标）
     * @param tilePath  瓦片文件路径
     * @throws SQLException 写入失败时抛出
     * @throws IOException  读取文件失败时抛出
     */
    public void putTile(int z, int x, int y, String tilePath) throws SQLException, IOException {
        byte[] data = Files.readAllBytes(new File(tilePath).toPath());
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)")) {
            stmt.setInt(1, z);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setBytes(4, data);
            stmt.executeUpdate();
        }
        new File(tilePath).delete();
    }

    /**
     * 将瓦片字节数据写入 MBTiles。
     *
     * @param z    缩放级别
     * @param x    瓦片列号
     * @param y    瓦片行号（TMS 坐标）
     * @param data 瓦片二进制数据
     * @throws SQLException 写入失败时抛出
     */
    public void putTileData(int z, int x, int y, byte[] data) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)")) {
            stmt.setInt(1, z);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setBytes(4, data);
            stmt.executeUpdate();
        }
    }

    @Override
    public void close() {
        IoUtil.close(connection);
    }
}
