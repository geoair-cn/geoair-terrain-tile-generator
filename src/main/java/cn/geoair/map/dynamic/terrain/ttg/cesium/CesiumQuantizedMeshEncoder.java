package cn.geoair.map.dynamic.terrain.ttg.cesium;

import cn.geoair.map.dynamic.terrain.ttg.cesium.model.Bounds;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Cesium quantized-mesh 编码器
 * <p>
 * 将高程网格数据编码为 Cesium quantized-mesh 地形格式。
 * <p>
 * quantized-mesh 格式规范：https://github.com/CesiumGS/quantized-mesh
 * <p>
 * 编码流程：
 * 1. 计算高程范围（minHeight, maxHeight）
 * 2. 构建量化顶点（u, v, height 量化为 0-32767）
 * 3. 构建三角网（规则网格的三角剖分）
 * 4. 使用 high-water-mark 编码重排顶点顺序
 * 5. 写入文件头（中心点、包围球、高程范围）
 * 6. 写入顶点数据（u, v, height）
 * 7. 写入三角网索引（high-water-mark 编码）
 * 8. 写入边缘索引（用于瓦片接缝处理）
 * 9. 按配置选择是否 gzip 压缩整个数据
 * <p>
 * 文件格式概览：
 * +-------------------+
 * | Header            |  中心点(24字节) + 高程范围(8字节) + 包围球(32字节)
 * +-------------------+
 * | Vertex Count      |  4字节 (int32)
 * +-------------------+
 * | U coordinates     |  vertexCount * 2字节 (uint16)
 * +-------------------+
 * | V coordinates     |  vertexCount * 2字节 (uint16)
 * +-------------------+
 * | Heights           |  vertexCount * 2字节 (uint16)
 * +-------------------+
 * | Padding           |  对齐到4字节边界
 * +-------------------+
 * | Triangle Count    |  4字节 (int32)
 * +-------------------+
 * | Triangle Indices  |  high-water-mark 编码
 * +-------------------+
 * | Edge Indices      |  4个边缘的索引
 * +-------------------+
 * | Optional GZIP     |  可选 gzip 压缩整个数据
 * +-------------------+
 */
final class CesiumQuantizedMeshEncoder {
    /**
     * 量化最大值：顶点坐标和高程都量化到 [0, 32767] 范围
     * 使用 uint16 存储，节省空间
     */
    private static final int QUANTIZATION_MAX = 32767;

    /** WGS84 椭球体长半轴（赤道半径），单位：米 */
    private static final double SEMI_MAJOR_AXIS = 6378137.0;

    /** WGS84 椭球体短半轴（极半径），单位：米 */
    private static final double SEMI_MINOR_AXIS = 6356752.314245179;

    /** WGS84 椭球体第一偏心率平方，用于地理坐标转地心坐标 (ECEF) */
    private static final double ECCENTRICITY_SQUARED =
            1.0 - (SEMI_MINOR_AXIS * SEMI_MINOR_AXIS) / (SEMI_MAJOR_AXIS * SEMI_MAJOR_AXIS);

    private CesiumQuantizedMeshEncoder() {
    }

    /**
     * 将高程网格编码为 quantized-mesh 格式的字节数组
     * <p>
     * 编码流程：
     * 1. 验证输入网格大小（至少 2x2）
     * 2. 计算有效高程的最小值和最大值
     * 3. 构建量化顶点（将经纬度和高程量化为 0-32767）
     * 4. 构建规则网格的三角剖分
     * 5. 使用 high-water-mark 编码重排顶点（优化压缩率）
     * 6. 写入文件头和顶点数据
     * 7. 写入三角网索引和边缘索引
     * 8. 按配置选择是否 gzip 压缩
     *
     * @param heights 高程网格 [row][column]，无效值为 Float.NaN
     * @param bounds  瓦片的地理范围（经纬度）
     * @return 编码后的字节数组，如果全为无效值则返回 null
     * @throws IOException 编码过程中的 IO 错误
     */
    static byte[] encode(float[][] heights, Bounds bounds) throws IOException {
        return encode(heights, bounds, false);
    }

    /**
     * 将高程网格编码为 quantized-mesh 格式，可选择 gzip 压缩输出。
     *
     * @param gzip true 时压缩整个瓦片；false 时返回原始 quantized-mesh 二进制
     */
    static byte[] encode(float[][] heights, Bounds bounds, boolean gzip) throws IOException {
        int size = heights.length;
        if (size < 2 || heights[0].length != size) {
            throw new IllegalArgumentException("Height grid must be at least 2x2");
        }
        for (float[] row : heights) {
            if (row.length != size) {
                throw new IllegalArgumentException("Height grid must be square");
            }
        }

        // 如果整块没有任何有效高程，明确返回 null，让生成器跳过落盘。
        if (!containsFiniteHeight(heights)) {
            return null;
        }

        // 部分覆盖 tile / DEM 内部 NoData 不能简单替换成 minHeight，
        // 否则会在边界形成陡峭的“最低高程墙”。这里使用最近有效样本补齐网格。
        float[][] filledHeights = fillMissingHeightsNearest(heights);

        float minHeight = Float.POSITIVE_INFINITY;
        float maxHeight = Float.NEGATIVE_INFINITY;
        for (float[] row : filledHeights) {
            for (float height : row) {
                if (Float.isFinite(height)) {
                    minHeight = Math.min(minHeight, height);
                    maxHeight = Math.max(maxHeight, height);
                }
            }
        }

        Vertex[] sourceVertices = buildVertices(filledHeights, bounds, minHeight, maxHeight);
        Mesh mesh = reorderForHighWaterMark(sourceVertices, buildGridTriangles(size));

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        writeHeader(raw, mesh.vertices, bounds, minHeight, maxHeight);
        writeIntLE(raw, mesh.vertices.size());
        // quantized-mesh 顶点属性不是直接写入量化值，而是分别进行
        // ZigZag Delta 编码。Cesium 读取时会无条件执行对应的解码；若这里
        // 直接写原值，高程和坐标都会被错误还原。
        writeZigZagDelta(raw, mesh.vertices, Coordinate.U);
        writeZigZagDelta(raw, mesh.vertices, Coordinate.V);
        writeZigZagDelta(raw, mesh.vertices, Coordinate.HEIGHT);

        boolean use32Bit = mesh.vertices.size() > 65536;
        padToIndexAlignment(raw, use32Bit ? 4 : 2);
        writeTriangleIndices(raw, mesh.triangles, use32Bit);
        writeEdgeIndices(raw, edgeIndices(size, mesh.remap), use32Bit);

        if (!gzip) {
            return raw.toByteArray();
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(raw.size());
        try (GZIPOutputStream stream = new GZIPOutputStream(compressed)) {
            stream.write(raw.toByteArray());
        }
        return compressed.toByteArray();
    }

    /**
     * 判断网格中是否存在至少一个有效高程。
     */
    private static boolean containsFiniteHeight(float[][] heights) {
        for (float[] row : heights) {
            for (float value : row) {
                if (Float.isFinite(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 使用多源 BFS 将 NaN/Infinity 填为最近的有效高程。
     * 这样边界 tile 仍然保持规则网格，同时不会把缺失区硬压到 minHeight。
     */
    private static float[][] fillMissingHeightsNearest(float[][] source) {
        int rows = source.length;
        int cols = source[0].length;
        float[][] result = new float[rows][cols];
        int[][] nearestRow = new int[rows][cols];
        int[][] nearestCol = new int[rows][cols];
        boolean[][] visited = new boolean[rows][cols];
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        for (int r = 0; r < rows; r++) {
            result[r] = Arrays.copyOf(source[r], cols);
            Arrays.fill(nearestRow[r], -1);
            Arrays.fill(nearestCol[r], -1);
            for (int c = 0; c < cols; c++) {
                if (Float.isFinite(source[r][c])) {
                    visited[r][c] = true;
                    nearestRow[r][c] = r;
                    nearestCol[r][c] = c;
                    queue.addLast(new int[]{r, c});
                }
            }
        }

        final int[] dr = {-1, 1, 0, 0};
        final int[] dc = {0, 0, -1, 1};
        while (!queue.isEmpty()) {
            int[] current = queue.removeFirst();
            int r = current[0];
            int c = current[1];
            for (int i = 0; i < 4; i++) {
                int nr = r + dr[i];
                int nc = c + dc[i];
                if (nr < 0 || nr >= rows || nc < 0 || nc >= cols || visited[nr][nc]) {
                    continue;
                }
                visited[nr][nc] = true;
                nearestRow[nr][nc] = nearestRow[r][c];
                nearestCol[nr][nc] = nearestCol[r][c];
                queue.addLast(new int[]{nr, nc});
            }
        }

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!Float.isFinite(result[r][c])) {
                    int rr = nearestRow[r][c];
                    int cc = nearestCol[r][c];
                    if (rr >= 0 && cc >= 0) {
                        result[r][c] = source[rr][cc];
                    }
                }
            }
        }
        return result;
    }

    /**
     * 构建量化顶点数组
     * <p>
     * 将高程网格中的每个点转换为量化顶点，包括：
     * - u: 经度方向量化坐标 [0, 32767]
     * - v: 纬度方向量化坐标 [0, 32767]（从南向北递增）
     * - height: 高程量化值 [0, 32767]（相对于 min/max 范围）
     * - sourceHeight: 原始高程值（用于计算包围球）
     * - longitude, latitude: 原始经纬度（用于 ECEF 转换）
     *
     * @param heights   高程网格
     * @param bounds    瓦片地理范围
     * @param minHeight 有效高程最小值
     * @param maxHeight 有效高程最大值
     * @return 量化顶点数组
     */
    private static Vertex[] buildVertices(float[][] heights, Bounds bounds,
                                          float minHeight, float maxHeight) {
        int size = heights.length;
        Vertex[] vertices = new Vertex[size * size];
        double range = maxHeight - minHeight;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                float value = heights[row][col];
                int u = (int) Math.round(col * QUANTIZATION_MAX / (double) (size - 1));
                int v = (int) Math.round((size - 1 - row) * QUANTIZATION_MAX / (double) (size - 1));
                int h = range == 0.0 ? 0 : clamp(Math.round((value - minHeight) * QUANTIZATION_MAX / range));
                vertices[row * size + col] = new Vertex(u, v, h, value,
                        bounds.longitudeAt(col, size), bounds.latitudeAtNorthRow(row, size));
            }
        }
        return vertices;
    }

    /**
     * 构建规则网格的三角剖分
     * <p>
     * 将 nxn 的规则网格剖分为 (n-1)*(n-1)*2 个三角形。
     * 每个网格单元（四边形）被剖分为 2 个三角形：
     * <pre>
     * NW --- NE
     * |  \   |
     * |   \  |
     * SW --- SE
     * 三角形1: NW -> SW -> NE
     * 三角形2: NE -> SW -> SE
     * </pre>
     *
     * @param size 网格大小（如 65 表示 65x65 网格）
     * @return 三角形索引数组，每 3 个元素构成一个三角形
     */
    private static int[] buildGridTriangles(int size) {
        int[] triangles = new int[(size - 1) * (size - 1) * 6];
        int cursor = 0;
        for (int row = 0; row < size - 1; row++) {
            for (int col = 0; col < size - 1; col++) {
                int nw = row * size + col;
                int ne = nw + 1;
                int sw = nw + size;
                int se = sw + 1;
                triangles[cursor++] = nw;
                triangles[cursor++] = sw;
                triangles[cursor++] = ne;
                triangles[cursor++] = ne;
                triangles[cursor++] = sw;
                triangles[cursor++] = se;
            }
        }
        return triangles;
    }

    /**
     * 使用 high-water-mark 编码重排顶点顺序
     * <p>
     * high-water-mark 编码是一种压缩算法，用于减少三角网索引的存储空间。
     * <p>
     * 原理：
     * - 按三角形遍历顺序重新分配顶点编号
     * - 编码时存储：最高顶点编号 - 当前顶点编号
     * - 解码时：当前顶点编号 = 最高顶点编号 - 编码值
     * - 由于差值通常较小，可以使用更少的位数存储
     * <p>
     * 示例：
     * - 原始索引: [0, 5, 3, 3, 5, 8]
     * - 重排后:   [0, 1, 2, 2, 1, 3]
     * - 编码值:   [0, 0, 0, 0, 1, 0]  (最高-当前)
     *
     * @param vertices        原始顶点数组
     * @param sourceTriangles 原始三角形索引
     * @return 重排后的 Mesh 对象（包含新顶点、新索引和重映射表）
     */
    private static Mesh reorderForHighWaterMark(Vertex[] vertices, int[] sourceTriangles) {
        int[] remap = new int[vertices.length];
        java.util.Arrays.fill(remap, -1);
        List<Vertex> ordered = new ArrayList<>(vertices.length);
        int[] triangles = new int[sourceTriangles.length];
        for (int i = 0; i < sourceTriangles.length; i++) {
            int source = sourceTriangles[i];
            if (remap[source] < 0) {
                remap[source] = ordered.size();
                ordered.add(vertices[source]);
            }
            triangles[i] = remap[source];
        }
        return new Mesh(ordered, triangles, remap);
    }

    /**
     * 计算瓦片边缘索引
     * <p>
     * quantized-mesh 格式要求提供瓦片四个边缘的顶点索引，
     * 用于相邻瓦片之间的接缝处理（seam handling）。
     * <p>
     * 边缘顺序：[west, south, east, north]
     * - west:  西边缘，从北到南
     * - south: 南边缘，从东到西
     * - east:  东边缘，从南到北
     * - north: 北边缘，从西到东
     * <p>
     * 注意：边缘顶点顺序是特定的，用于确保相邻瓦片的边缘顶点匹配。
     *
     * @param size  网格大小
     * @param remap 顶点重映射表（high-water-mark 编码后的索引）
     * @return 四个边缘的索引数组 [4][size]
     */
    private static int[][] edgeIndices(int size, int[] remap) {
        int[] west = new int[size];
        int[] south = new int[size];
        int[] east = new int[size];
        int[] north = new int[size];
        for (int i = 0; i < size; i++) {
            west[i] = remap[(size - 1 - i) * size];
            south[i] = remap[(size - 1) * size + (size - 1 - i)];
            east[i] = remap[i * size + (size - 1)];
            north[i] = remap[size - 1 - i];
        }
        return new int[][]{west, south, east, north};
    }

    /**
     * 写入 quantized-mesh 88 字节 Header。
     * HorizonOcclusionPoint 使用与 Cesium EllipsoidalOccluder 一致的
     * ellipsoid-scaled space horizon culling 算法计算。
     */
    private static void writeHeader(ByteArrayOutputStream out, List<Vertex> vertices,
                                    Bounds bounds, float minHeight, float maxHeight) {
        // 使用瓦片地理中心 + 高程中值作为一个稳定的包围球中心。
        // 半径取到全部顶点的最大距离，因此虽然不是最小包围球，但一定覆盖所有顶点。
        double[] center = toEcef(
                (bounds.west() + bounds.east()) * 0.5,
                (bounds.south() + bounds.north()) * 0.5,
                (minHeight + maxHeight) * 0.5
        );

        double radius = 0.0;
        List<double[]> ecefPositions = new ArrayList<>(vertices.size());
        for (Vertex vertex : vertices) {
            double[] position = toEcef(vertex.longitude, vertex.latitude, vertex.sourceHeight);
            ecefPositions.add(position);
            radius = Math.max(radius, distance(center, position));
        }

        double[] horizon = computeHorizonCullingPoint(center, ecefPositions, minHeight);

        writeDoubleLE(out, center[0]);
        writeDoubleLE(out, center[1]);
        writeDoubleLE(out, center[2]);
        writeFloatLE(out, minHeight);
        writeFloatLE(out, maxHeight);

        writeDoubleLE(out, center[0]);
        writeDoubleLE(out, center[1]);
        writeDoubleLE(out, center[2]);
        writeDoubleLE(out, radius);

        writeDoubleLE(out, horizon[0]);
        writeDoubleLE(out, horizon[1]);
        writeDoubleLE(out, horizon[2]);
    }

    /**
     * 计算 quantized-mesh Header 中的 HorizonOcclusionPoint。
     * 返回值位于 ellipsoid-scaled ECEF 坐标系。
     *
     * 算法对应 CesiumJS EllipsoidalOccluder.computeHorizonCullingPointPossiblyUnderEllipsoid。
     */
    private static double[] computeHorizonCullingPoint(double[] directionToPointEcef,
                                                       List<double[]> positionsEcef,
                                                       double minimumHeight) {
        // 当最低高程低于椭球面时，Cesium 会使用按 minimumHeight 收缩后的椭球。
        double radiusX = SEMI_MAJOR_AXIS;
        double radiusY = SEMI_MAJOR_AXIS;
        double radiusZ = SEMI_MINOR_AXIS;
        if (minimumHeight < 0.0 && SEMI_MINOR_AXIS > -minimumHeight) {
            radiusX += minimumHeight;
            radiusY += minimumHeight;
            radiusZ += minimumHeight;
        }

        double[] scaledDirection = new double[]{
                directionToPointEcef[0] / radiusX,
                directionToPointEcef[1] / radiusY,
                directionToPointEcef[2] / radiusZ
        };
        normalizeInPlace(scaledDirection);

        double resultMagnitude = 0.0;
        for (double[] position : positionsEcef) {
            double sx = position[0] / radiusX;
            double sy = position[1] / radiusY;
            double sz = position[2] / radiusZ;

            double magnitudeSquared = sx * sx + sy * sy + sz * sz;
            double magnitude = Math.sqrt(magnitudeSquared);
            if (!(magnitude > 0.0) || !Double.isFinite(magnitude)) {
                continue;
            }

            double dx = sx / magnitude;
            double dy = sy / magnitude;
            double dz = sz / magnitude;

            // Cesium 算法中，位于椭球以下的点在这个计算里按椭球表面处理。
            magnitudeSquared = Math.max(1.0, magnitudeSquared);
            magnitude = Math.max(1.0, magnitude);

            double cosAlpha = dx * scaledDirection[0]
                              + dy * scaledDirection[1]
                              + dz * scaledDirection[2];

            double crossX = dy * scaledDirection[2] - dz * scaledDirection[1];
            double crossY = dz * scaledDirection[0] - dx * scaledDirection[2];
            double crossZ = dx * scaledDirection[1] - dy * scaledDirection[0];
            double sinAlpha = Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);

            double cosBeta = 1.0 / magnitude;
            double sinBeta = Math.sqrt(Math.max(0.0, magnitudeSquared - 1.0)) * cosBeta;
            double denominator = cosAlpha * cosBeta - sinAlpha * sinBeta;

            if (denominator <= 0.0 || !Double.isFinite(denominator)) {
                // 极低级别或方向退化时采用保守值，宁可少裁剪也不要错误隐藏 terrain。
                return multiply(scaledDirection, 1.0e9);
            }

            double candidateMagnitude = 1.0 / denominator;
            if (!Double.isFinite(candidateMagnitude) || candidateMagnitude <= 0.0) {
                return multiply(scaledDirection, 1.0e9);
            }
            resultMagnitude = Math.max(resultMagnitude, candidateMagnitude);
        }

        if (!(resultMagnitude > 0.0) || !Double.isFinite(resultMagnitude)) {
            return multiply(scaledDirection, 1.0e9);
        }
        return multiply(scaledDirection, resultMagnitude);
    }

    private static void normalizeInPlace(double[] vector) {
        double magnitude = Math.sqrt(vector[0] * vector[0]
                                     + vector[1] * vector[1]
                                     + vector[2] * vector[2]);
        if (!(magnitude > 0.0) || !Double.isFinite(magnitude)) {
            vector[0] = 1.0;
            vector[1] = 0.0;
            vector[2] = 0.0;
            return;
        }
        vector[0] /= magnitude;
        vector[1] /= magnitude;
        vector[2] /= magnitude;
    }

    private static double[] multiply(double[] vector, double scalar) {
        return new double[]{
                vector[0] * scalar,
                vector[1] * scalar,
                vector[2] * scalar
        };
    }

    /**
     * 写入三角形索引（使用 high-water-mark 编码）
     * <p>
     * high-water-mark 编码格式：
     * 1. 首先写入三角形数量（int32）
     * 2. 然后写入每个顶点的编码值
     * <p>
     * 编码规则：
     * - 维护一个 highest 变量，表示已处理的最大顶点编号
     * - 对于每个顶点索引 index，编码值 = highest - index
     * - 如果编码值 == 0，说明遇到了新顶点，highest++
     * - 编码值必须 >= 0，否则说明顶点顺序错误
     * <p>
     * 索引大小：
     * - 顶点数 <= 65536: 使用 uint16 (2字节)
     * - 顶点数 > 65536: 使用 int32 (4字节)
     *
     * @param out       输出流
     * @param triangles 三角形索引数组
     * @param use32Bit  是否使用 32 位索引
     */
    private static void writeTriangleIndices(ByteArrayOutputStream out, int[] triangles, boolean use32Bit) {
        writeIntLE(out, triangles.length / 3);
        int highest = 0;
        for (int index : triangles) {
            int code = highest - index;
            if (code < 0) throw new IllegalStateException("Vertex order violates high-water-mark encoding");
            writeIndex(out, code, use32Bit);
            if (code == 0) highest++;
        }
    }

    /**
     * 写入边缘索引
     * <p>
     * 写入瓦片四个边缘的顶点索引，用于相邻瓦片的接缝处理。
     * 每个边缘的格式：
     * 1. 边缘顶点数量 (int32)
     * 2. 顶点索引列表（使用与三角形索引相同的位宽）
     *
     * @param out      输出流
     * @param edges    四个边缘的索引数组 [4][n]
     * @param use32Bit 是否使用 32 位索引
     */
    private static void writeEdgeIndices(ByteArrayOutputStream out, int[][] edges, boolean use32Bit) {
        for (int[] edge : edges) {
            writeIntLE(out, edge.length);
            for (int index : edge) writeIndex(out, index, use32Bit);
        }
    }

    private static void padToIndexAlignment(ByteArrayOutputStream out, int alignment) {
        while (out.size() % alignment != 0) out.write(0);
    }

    private static void writeIndex(ByteArrayOutputStream out, int value, boolean use32Bit) {
        if (use32Bit) writeIntLE(out, value);
        else writeUShortLE(out, value);
    }

    /**
     * 按 quantized-mesh 规范写入一个顶点属性数组。
     * 每个量化值与前一顶点同一属性的差值先 ZigZag 编码，再以 uint16 小端写入。
     */
    private static void writeZigZagDelta(ByteArrayOutputStream out, List<Vertex> vertices,
                                         Coordinate coordinate) {
        int previous = 0;
        for (Vertex vertex : vertices) {
            int value = coordinate.valueOf(vertex);
            int delta = value - previous;
            int zigZag = (delta << 1) ^ (delta >> 31);
            writeUShortLE(out, zigZag);
            previous = value;
        }
    }

    private static int clamp(long value) {
        return (int) Math.max(0, Math.min(QUANTIZATION_MAX, value));
    }

    private enum Coordinate {
        U {
            @Override
            int valueOf(Vertex vertex) {
                return vertex.u;
            }
        },
        V {
            @Override
            int valueOf(Vertex vertex) {
                return vertex.v;
            }
        },
        HEIGHT {
            @Override
            int valueOf(Vertex vertex) {
                return vertex.height;
            }
        };

        abstract int valueOf(Vertex vertex);
    }

    private static double[] toEcef(double longitudeDegrees, double latitudeDegrees, double height) {
        double lon = Math.toRadians(longitudeDegrees);
        double lat = Math.toRadians(latitudeDegrees);
        double sin = Math.sin(lat);
        double cos = Math.cos(lat);
        double radius = SEMI_MAJOR_AXIS / Math.sqrt(1.0 - ECCENTRICITY_SQUARED * sin * sin);
        return new double[]{(radius + height) * cos * Math.cos(lon),
                (radius + height) * cos * Math.sin(lon),
                (radius * (1.0 - ECCENTRICITY_SQUARED) + height) * sin};
    }

    private static double distance(double[] left, double[] right) {
        double x = left[0] - right[0], y = left[1] - right[1], z = left[2] - right[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void writeDoubleLE(ByteArrayOutputStream out, double value) {
        writeLongLE(out, Double.doubleToLongBits(value));
    }

    private static void writeFloatLE(ByteArrayOutputStream out, float value) {
        writeIntLE(out, Float.floatToIntBits(value));
    }

    private static void writeLongLE(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) out.write((int) (value >>> (i * 8)) & 0xFF);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < 4; i++) out.write((value >>> (i * 8)) & 0xFF);
    }

    private static void writeUShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    /**
     * 量化顶点
     * <p>
     * 存储顶点的量化坐标和原始地理坐标。
     * 量化坐标用于文件存储（节省空间），
     * 原始坐标用于计算包围球和地平线遮挡点。
     */
    private static class Vertex {
        /** 经度方向量化坐标 [0, 32767] */
        final int u;
        /** 纬度方向量化坐标 [0, 32767]（从南向北递增） */
        final int v;
        /** 高程量化值 [0, 32767] */
        final int height;
        /** 原始高程值（米），用于计算包围球半径 */
        final float sourceHeight;
        /** 原始经度（度），用于 ECEF 转换 */
        final double longitude;
        /** 原始纬度（度），用于 ECEF 转换 */
        final double latitude;

        Vertex(int u, int v, int height, float sourceHeight, double longitude, double latitude) {
            this.u = u;
            this.v = v;
            this.height = height;
            this.sourceHeight = sourceHeight;
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    /**
     * 重排后的网格数据
     * <p>
     * 包含 high-water-mark 编码后的顶点列表、三角形索引和顶点重映射表。
     */
    private static class Mesh {
        /** 重排后的顶点列表 */
        final List<Vertex> vertices;
        /** 重排后的三角形索引 */
        final int[] triangles;
        /** 顶点重映射表：原始索引 -> 新索引 */
        final int[] remap;

        Mesh(List<Vertex> vertices, int[] triangles, int[] remap) {
            this.vertices = vertices;
            this.triangles = triangles;
            this.remap = remap;
        }
    }
}
