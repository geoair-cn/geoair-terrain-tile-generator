package cn.geoair.map.dynamic.terrain.ttg.png;

import cn.hutool.core.io.IoUtil;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;

public class MBTilesUtil implements Closeable {

    private Connection connection;
    private String path;

    public static MBTilesUtil open(String mbpath, String mode) throws SQLException {
        MBTilesUtil util = new MBTilesUtil();
        util.path = mbpath;
        String url = "jdbc:sqlite:" + mbpath;
        if ("rw".equals(mode) || "rwc".equals(mode)) {
            File file = new File(mbpath);
            if (!file.exists()) {
                util.connection = DriverManager.getConnection(url);
                util.initDatabase();
            } else {
                util.connection = DriverManager.getConnection(url);
            }
        } else {
            util.connection = DriverManager.getConnection(url);
        }
        return util;
    }

    private void initDatabase() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS tiles (" +
                         "zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, " +
                         "PRIMARY KEY (zoom_level, tile_column, tile_row))");
            stmt.execute("CREATE TABLE IF NOT EXISTS metadata (" +
                         "name TEXT, value TEXT, PRIMARY KEY (name))");
        }
    }

    public void startWriting() throws SQLException {
        connection.setAutoCommit(false);
    }

    public void stopWriting() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

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
