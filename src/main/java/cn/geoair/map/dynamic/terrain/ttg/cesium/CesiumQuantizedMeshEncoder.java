package cn.geoair.map.dynamic.terrain.ttg.cesium;

import cn.geoair.map.dynamic.terrain.ttg.model.Bounds;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

final class CesiumQuantizedMeshEncoder {
    private static final int QUANTIZATION_MAX = 32767;
    private static final double SEMI_MAJOR_AXIS = 6378137.0;
    private static final double SEMI_MINOR_AXIS = 6356752.314245179;
    private static final double ECCENTRICITY_SQUARED =
            1.0 - (SEMI_MINOR_AXIS * SEMI_MINOR_AXIS) / (SEMI_MAJOR_AXIS * SEMI_MAJOR_AXIS);

    private CesiumQuantizedMeshEncoder() {
    }

    static byte[] encode(float[][] heights, Bounds bounds) throws IOException {
        int size = heights.length;
        if (size < 2 || heights[0].length != size) {
            throw new IllegalArgumentException("Height grid must be at least 2x2");
        }
        float minHeight = Float.POSITIVE_INFINITY;
        float maxHeight = Float.NEGATIVE_INFINITY;
        for (float[] row : heights) {
            if (row.length != size) throw new IllegalArgumentException("Height grid must be square");
            for (float height : row)
                if (!Float.isNaN(height)) {
                    minHeight = Math.min(minHeight, height);
                    maxHeight = Math.max(maxHeight, height);
                }
        }
        if (!Float.isFinite(minHeight)) return null;

        Vertex[] sourceVertices = buildVertices(heights, bounds, minHeight, maxHeight);
        Mesh mesh = reorderForHighWaterMark(sourceVertices, buildGridTriangles(size));

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        writeHeader(raw, mesh.vertices, bounds, minHeight, maxHeight);
        writeIntLE(raw, mesh.vertices.size());
        for (Vertex vertex : mesh.vertices) writeUShortLE(raw, vertex.u);
        for (Vertex vertex : mesh.vertices) writeUShortLE(raw, vertex.v);
        for (Vertex vertex : mesh.vertices) writeUShortLE(raw, vertex.height);

        boolean use32Bit = mesh.vertices.size() > 65536;
        padToIndexAlignment(raw, use32Bit ? 4 : 2);
        writeTriangleIndices(raw, mesh.triangles, use32Bit);
        writeEdgeIndices(raw, edgeIndices(size, mesh.remap), use32Bit);

        ByteArrayOutputStream gzip = new ByteArrayOutputStream(raw.size());
        try (GZIPOutputStream stream = new GZIPOutputStream(gzip)) {
            stream.write(raw.toByteArray());
        }
        return gzip.toByteArray();
    }

    private static Vertex[] buildVertices(float[][] heights, Bounds bounds,
                                          float minHeight, float maxHeight) {
        int size = heights.length;
        Vertex[] vertices = new Vertex[size * size];
        double range = maxHeight - minHeight;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                float value = Float.isNaN(heights[row][col]) ? minHeight : heights[row][col];
                int u = (int) Math.round(col * QUANTIZATION_MAX / (double) (size - 1));
                int v = (int) Math.round((size - 1 - row) * QUANTIZATION_MAX / (double) (size - 1));
                int h = range == 0.0 ? 0 : clamp(Math.round((value - minHeight) * QUANTIZATION_MAX / range));
                vertices[row * size + col] = new Vertex(u, v, h, value,
                        bounds.longitudeAt(col, size), bounds.latitudeAtNorthRow(row, size));
            }
        }
        return vertices;
    }

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

    private static void writeHeader(ByteArrayOutputStream out, List<Vertex> vertices,
                                    Bounds bounds, float minHeight, float maxHeight) {
        double[] center = toEcef((bounds.west() + bounds.east()) * .5,
                (bounds.south() + bounds.north()) * .5, (minHeight + maxHeight) * .5);
        double radius = 0.0;
        for (Vertex vertex : vertices) {
            radius = Math.max(radius, distance(center, toEcef(vertex.longitude, vertex.latitude, vertex.sourceHeight)));
        }
        writeDoubleLE(out, center[0]);
        writeDoubleLE(out, center[1]);
        writeDoubleLE(out, center[2]);
        writeFloatLE(out, minHeight);
        writeFloatLE(out, maxHeight);
        writeDoubleLE(out, center[0]);
        writeDoubleLE(out, center[1]);
        writeDoubleLE(out, center[2]);
        writeDoubleLE(out, radius);
        writeDoubleLE(out, center[0] / SEMI_MAJOR_AXIS);
        writeDoubleLE(out, center[1] / SEMI_MAJOR_AXIS);
        writeDoubleLE(out, center[2] / SEMI_MINOR_AXIS);
    }

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

    private static int clamp(long value) {
        return (int) Math.max(0, Math.min(QUANTIZATION_MAX, value));
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

    private static class Vertex {
        final int u, v, height;
        final float sourceHeight;
        final double longitude, latitude;

        Vertex(int u, int v, int height, float sourceHeight, double longitude, double latitude) {
            this.u = u;
            this.v = v;
            this.height = height;
            this.sourceHeight = sourceHeight;
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    private static class Mesh {
        final List<Vertex> vertices;
        final int[] triangles;
        final int[] remap;

        Mesh(List<Vertex> vertices, int[] triangles, int[] remap) {
            this.vertices = vertices;
            this.triangles = triangles;
            this.remap = remap;
        }
    }
}
