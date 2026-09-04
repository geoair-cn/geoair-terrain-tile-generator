package cn.geoair.map.dynamic.terrain.ttg.cesium;

import cn.geoair.map.dynamic.terrain.ttg.model.Bounds;
import cn.geoair.map.dynamic.terrain.ttg.model.CesiumOptions;
import cn.geoair.map.dynamic.terrain.ttg.model.DatasetInfo;
import cn.geoair.map.dynamic.terrain.ttg.model.Range;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconst;
import org.gdal.osr.SpatialReference;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class CesiumTerrainGenerator {
    static { gdal.AllRegister(); }

    private CesiumTerrainGenerator() {}

    public enum MeshPrecision {
        LOW(33), MEDIUM(65), HIGH(129), ULTRA(257);
        private final int gridSize;
        MeshPrecision(int gridSize) { this.gridSize = gridSize; }
        public int gridSize() { return gridSize; }
    }

    public static void generate(String input, String outputDirectory, CesiumOptions options) throws Exception {
        File output = new File(outputDirectory);
        if (output.getName().toLowerCase().endsWith(".mbtiles")) {
            throw new IllegalArgumentException("Cesium quantized-mesh does not support MBTiles output");
        }
        if (options.cleanOutput()) emptyDirectory(output.toPath());
        Files.createDirectories(output.toPath());

        Dataset inputDataset = gdal.Open(input);
        if (inputDataset == null) throw new IllegalArgumentException("Cannot open DEM: " + input);
        Dataset workingDataset = null;
        String workingPath = null;
        try {
            int sourceEpsg = authorityEpsg(inputDataset);
            if (sourceEpsg == 4326) {
                workingDataset = inputDataset;
                inputDataset = null;
                workingPath = workingDataset.GetDescription();
            } else {
                File parent = output.getAbsoluteFile().getParentFile();
                String name = (options.reprojectFileName() == null || options.reprojectFileName().trim().isEmpty()
                        || "UUID".equalsIgnoreCase(options.reprojectFileName()))
                        ? java.util.UUID.randomUUID().toString() : options.reprojectFileName();
                workingPath = new File(parent, name + ".tif").getAbsolutePath();
                reprojectTo4326(inputDataset, workingPath, options.resampling());
                workingDataset = gdal.Open(workingPath);
                if (workingDataset == null) throw new IllegalStateException("Cannot open reprojected result: " + workingPath);
            }

            DatasetInfo datasetInfo = DatasetInfo.from(workingDataset);
            Map<Integer, Range> availability = buildAvailability(datasetInfo, options);
            int workers = Math.min(Runtime.getRuntime().availableProcessors() * 2, 16);
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            try {
                List<Future<Void>> tasks = new ArrayList<>();
                for (Map.Entry<Integer, Range> entry : availability.entrySet()) {
                    int zoom = entry.getKey();
                    Range range = entry.getValue();
                    for (int x = range.minX(); x <= range.maxX(); x++) {
                        for (int y = range.minY(); y <= range.maxY(); y++) {
                            final int tileX = x, tileY = y;
                            tasks.add(executor.submit(new CesiumTileTask(workingPath, datasetInfo, output.toPath(),
                                    zoom, tileX, tileY, options.precision().gridSize())));
                        }
                    }
                }
                for (Future<Void> task : tasks) task.get();
            } finally {
                executor.shutdown();
            }
            writeLayerJson(output.toPath(), options, datasetInfo, availability);
        } finally {
            if (inputDataset != null) inputDataset.delete();
            if (workingDataset != null) workingDataset.delete();
        }
    }

    private static Map<Integer, Range> buildAvailability(DatasetInfo info, CesiumOptions options) {
        Map<Integer, Range> result = new LinkedHashMap<>();
        for (int zoom = options.minZoom(); zoom <= options.maxZoom(); zoom++) {
            result.put(zoom, CesiumTileMath.range(info.west(), info.south(), info.east(), info.north(), zoom));
        }
        return result;
    }

    private static void writeLayerJson(Path output, CesiumOptions options, DatasetInfo info,
                                       Map<Integer, Range> availability) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode root = mapper.createObjectNode();
        root.put("format", "quantized-mesh-1.0");
        root.put("version", "1.0.0");
        root.put("scheme", "tms");
        root.put("projection", "EPSG:4326");
        root.put("minzoom", options.minZoom());
        root.put("maxzoom", options.maxZoom());
        root.putArray("tiles").add("{z}/{x}/{y}.terrain");
        root.putArray("bounds").add(info.west()).add(info.south()).add(info.east()).add(info.north());
        ArrayNode available = root.putArray("available");
        for (Range range : availability.values()) {
            ArrayNode level = available.addArray();
            level.addObject().put("startX", range.minX()).put("startY", range.minY())
                    .put("endX", range.maxX()).put("endY", range.maxY());
        }
        mapper.writeValue(output.resolve("layer.json").toFile(), root);
    }

    private static void reprojectTo4326(Dataset source, String destination, int resampling) {
        SpatialReference target = new SpatialReference();
        target.ImportFromEPSG(4326);
        SpatialReference current = source.GetProjectionRef() == null ? null : new SpatialReference(source.GetProjectionRef());
        Dataset warped = gdal.AutoCreateWarpedVRT(source, current == null ? null : current.ExportToWkt(),
                target.ExportToWkt(), gdalResampling(resampling));
        if (warped == null) throw new IllegalStateException("Cannot create EPSG:4326 warped VRT");
        try {
            Driver driver = source.GetDriver();
            Dataset copied = driver.CreateCopy(destination, warped);
            if (copied == null) throw new IllegalStateException("Cannot write reprojected TIFF: " + destination);
            copied.delete();
        } finally {
            warped.delete();
        }
    }

    private static int gdalResampling(int value) {
        switch (value) {
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

    private static int authorityEpsg(Dataset dataset) {
        if (dataset.GetProjectionRef() == null || dataset.GetProjectionRef().trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(new SpatialReference(dataset.GetProjectionRef()).GetAuthorityCode(null));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void emptyDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path current, IOException exception) throws IOException {
                Files.delete(current); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static class CesiumTileTask implements Callable<Void> {
        private final String datasetPath;
        private final DatasetInfo dataset;
        private final Path output;
        private final int zoom;
        private final int x;
        private final int y;
        private final int gridSize;

        CesiumTileTask(String datasetPath, DatasetInfo dataset, Path output, int zoom,
                       int x, int y, int gridSize) {
            this.datasetPath = datasetPath;
            this.dataset = dataset;
            this.output = output;
            this.zoom = zoom;
            this.x = x;
            this.y = y;
            this.gridSize = gridSize;
        }

        @Override
        public Void call() throws Exception {
            Dataset source = gdal.Open(datasetPath);
            if (source == null) throw new IllegalStateException("Cannot open DEM in worker thread: " + datasetPath);
            try {
                float[][] heights = sample(source.GetRasterBand(1), CesiumTileMath.bounds(zoom, x, y), dataset, gridSize);
                byte[] tile = CesiumQuantizedMeshEncoder.encode(heights, CesiumTileMath.bounds(zoom, x, y));
                if (tile != null) {
                    Path target = output.resolve(Integer.toString(zoom)).resolve(Integer.toString(x))
                            .resolve(y + ".terrain");
                    Files.createDirectories(target.getParent());
                    Files.write(target, tile);
                }
            } finally {
                source.delete();
            }
            return null;
        }
    }

    private static float[][] sample(Band band, Bounds bounds, DatasetInfo info, int gridSize) {
        int readX = Math.max(0, (int) Math.floor((bounds.west() - info.originX()) / info.resolutionX()));
        int readY = Math.max(0, (int) Math.floor((bounds.north() - info.originY()) / info.resolutionY()));
        int readEndX = Math.min(info.width(), (int) Math.ceil((bounds.east() - info.originX()) / info.resolutionX()));
        int readEndY = Math.min(info.height(), (int) Math.ceil((bounds.south() - info.originY()) / info.resolutionY()));
        if (readEndX <= readX || readEndY <= readY) {
            float[][] noData = new float[gridSize][gridSize];
            for (float[] row : noData) java.util.Arrays.fill(row, Float.NaN);
            return noData;
        }
        float[] values = new float[gridSize * gridSize];
        band.ReadRaster(readX, readY, readEndX - readX, readEndY - readY,
                gridSize, gridSize, gdalconst.GDT_Float32, values);
        Double[] noData = new Double[1];
        band.GetNoDataValue(noData);
        float[][] result = new float[gridSize][gridSize];
        for (int row = 0; row < gridSize; row++) {
            for (int column = 0; column < gridSize; column++) {
                float value = values[row * gridSize + column];
                result[row][column] = noData[0] != null && Double.compare(value, noData[0]) == 0 ? Float.NaN : value;
            }
        }
        return result;
    }
}
