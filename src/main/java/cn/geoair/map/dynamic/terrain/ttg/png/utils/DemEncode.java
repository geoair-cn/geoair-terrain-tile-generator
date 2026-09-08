package cn.geoair.map.dynamic.terrain.ttg.png.utils;

public class DemEncode {

    public static final int[] INVALID_COLOR = {1, 134, 160};

    public static int[] mapboxEncode(double height) {
        int value = (int) Math.floor((height + 10000) * 10);
        int r = value >> 16;
        int g = (value >> 8) & 0x0000FF;
        int b = value & 0x0000FF;
        return new int[]{r, g, b};
    }

    public static double mapboxDecode(int[] color) {
        return -10000 + ((color[0] * 256 * 256 + color[1] * 256 + color[2]) * 0.1);
    }

    public static int[] terrariumEncode(double height) {
        height += 32768;
        int r = (int) Math.floor(height / 256.0);
        int g = (int) Math.floor(height % 256);
        int b = (int) Math.floor((height - Math.floor(height)) * 256.0);
        return new int[]{r, g, b};
    }

    public static double terrariumDecode(int[] color) {
        return (color[0] * 256 + color[1] + color[2] / 256.0) - 32768;
    }

}
