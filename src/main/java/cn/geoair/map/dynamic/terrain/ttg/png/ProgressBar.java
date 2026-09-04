package cn.geoair.map.dynamic.terrain.ttg.png;

public class ProgressBar {
    private final int width;
    private final String prefix;
    private long total;
    private long current;
    private boolean completed;

    public ProgressBar(int width, String prefix) {
        this.width = width;
        this.prefix = prefix;
        this.total = 0;
        this.current = 0;
        this.completed = false;
    }

    public void setTaskTotal(long total) {
        this.total = total;
    }

    public void render(long current) {
        this.current = current;
        if (total == 0) return;
        int progress = (int) (current * 100.0 / total);
        int filled = (int) (width * current / total);
        int empty = width - filled;
        StringBuilder sb = new StringBuilder();
        sb.append('\r');
        sb.append(prefix);
        sb.append(" [");
        for (int i = 0; i < filled; i++) sb.append('=');
        for (int i = 0; i < empty; i++) sb.append(' ');
        sb.append("] ");
        sb.append(progress).append("%");
        sb.append(" (").append(current).append("/").append(total).append(")");
        System.out.print(sb.toString());
        if (current >= total && !completed) {
            completed = true;
            System.out.println();
        }
    }
}
