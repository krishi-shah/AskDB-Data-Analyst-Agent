package ca.yorku.askdb.charts;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

/** Draws a ChartSpec with JFreeChart. Used by the GUI chart panel and by the CLI to save images. */
public final class ChartRenderer {
    private static final int MAX_TABLE_ROWS = 20;

    private ChartRenderer() {
    }

    /** Returns null for table charts, which the GUI shows as a JTable. */
    public static JFreeChart toJFreeChart(ChartSpec spec) {
        boolean legend = spec.series().size() > 1;
        switch (spec.chartType()) {
            case "pie" -> {
                DefaultPieDataset<String> data = new DefaultPieDataset<>();
                List<Object> values = spec.series().values().iterator().next();
                for (int i = 0; i < spec.labels().size(); i++) {
                    data.setValue(String.valueOf(spec.labels().get(i)), number(values.get(i)));
                }
                JFreeChart chart = org.jfree.chart.ChartFactory.createPieChart(spec.title(), data, true, true, false);
                @SuppressWarnings("unchecked")
                PiePlot<String> plot = (PiePlot<String>) chart.getPlot();
                plot.setLabelGenerator(new StandardPieSectionLabelGenerator("{0}: {2}"));
                return style(chart);
            }
            case "scatter" -> {
                XYSeriesCollection data = new XYSeriesCollection();
                for (Map.Entry<String, List<Object>> e : spec.series().entrySet()) {
                    XYSeries s = new XYSeries(e.getKey());
                    for (int i = 0; i < spec.labels().size(); i++) {
                        s.add(number(spec.labels().get(i)), number(e.getValue().get(i)));
                    }
                    data.addSeries(s);
                }
                return style(org.jfree.chart.ChartFactory.createScatterPlot(spec.title(), spec.xLabel(),
                        spec.yLabel(), data, PlotOrientation.VERTICAL, legend, true, false));
            }
            case "line", "bar" -> {
                DefaultCategoryDataset data = new DefaultCategoryDataset();
                for (Map.Entry<String, List<Object>> e : spec.series().entrySet()) {
                    for (int i = 0; i < spec.labels().size(); i++) {
                        data.addValue(number(e.getValue().get(i)), e.getKey(), uniqueLabel(spec.labels(), i));
                    }
                }
                JFreeChart chart = spec.chartType().equals("line")
                        ? org.jfree.chart.ChartFactory.createLineChart(spec.title(), spec.xLabel(), spec.yLabel(),
                        data, PlotOrientation.VERTICAL, legend, true, false)
                        : org.jfree.chart.ChartFactory.createBarChart(spec.title(), spec.xLabel(), spec.yLabel(),
                        data, PlotOrientation.VERTICAL, legend, true, false);
                CategoryPlot plot = (CategoryPlot) chart.getPlot();
                if (plot.getRenderer() instanceof BarRenderer bars) {
                    bars.setBarPainter(new StandardBarPainter());
                    bars.setShadowVisible(false);
                    bars.setSeriesPaint(0, new Color(0x2f6fb0));
                }
                plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
                CategoryAxis axis = plot.getDomainAxis();
                if (spec.labels().size() > 6) {
                    axis.setCategoryLabelPositions(CategoryLabelPositions.UP_45);
                }
                return style(chart);
            }
            default -> {
                return null;
            }
        }
    }

    public static Path savePng(ChartSpec spec, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        JFreeChart chart = toJFreeChart(spec);
        if (chart != null) {
            ChartUtils.saveChartAsPNG(path.toFile(), chart, 880, 550);
        } else {
            ImageIO.write(tableImage(spec), "png", path.toFile());
        }
        return path;
    }

    private static JFreeChart style(JFreeChart chart) {
        chart.setBackgroundPaint(Color.WHITE);
        chart.getPlot().setBackgroundPaint(Color.WHITE);
        return chart;
    }

    /** Category labels must be unique, so repeated labels get a counter. */
    private static String uniqueLabel(List<Object> labels, int i) {
        String label = String.valueOf(labels.get(i));
        int seen = 0;
        for (int j = 0; j < i; j++) {
            if (String.valueOf(labels.get(j)).equals(label)) {
                seen++;
            }
        }
        return seen == 0 ? label : label + " (" + (seen + 1) + ")";
    }

    private static double number(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static BufferedImage tableImage(ChartSpec spec) {
        List<List<String>> cells = new ArrayList<>();
        cells.add(spec.columns());
        for (List<Object> row : spec.rows().subList(0, Math.min(MAX_TABLE_ROWS, spec.rows().size()))) {
            cells.add(row.stream().map(v -> v == null ? "NULL" : String.valueOf(v)).toList());
        }
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        FontMetrics fm = probe.createGraphics().getFontMetrics(font);
        int cols = spec.columns().size();
        int[] widths = new int[cols];
        for (List<String> row : cells) {
            for (int c = 0; c < cols && c < row.size(); c++) {
                widths[c] = Math.max(widths[c], fm.stringWidth(row.get(c)) + 16);
            }
        }
        int rowH = fm.getHeight() + 8;
        int width = Math.max(300, java.util.Arrays.stream(widths).sum() + 20);
        int height = rowH * (cells.size() + 2) + 10;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(font.deriveFont(Font.BOLD));
        String title = spec.title() + (spec.rows().size() > MAX_TABLE_ROWS ? " (first " + MAX_TABLE_ROWS + ")" : "");
        g.drawString(title, 10, rowH - 6);
        for (int r = 0; r < cells.size(); r++) {
            int y = rowH * (r + 1) + 10;
            g.setFont(r == 0 ? font.deriveFont(Font.BOLD) : font);
            g.setColor(r == 0 ? new Color(230, 230, 230) : Color.WHITE);
            g.fillRect(10, y, width - 20, rowH);
            g.setColor(Color.GRAY);
            g.drawRect(10, y, width - 20, rowH);
            g.setColor(Color.BLACK);
            int x = 10;
            for (int c = 0; c < cols && c < cells.get(r).size(); c++) {
                g.drawString(cells.get(r).get(c), x + 8, y + rowH - 8);
                x += widths[c];
            }
        }
        g.dispose();
        return img;
    }
}
