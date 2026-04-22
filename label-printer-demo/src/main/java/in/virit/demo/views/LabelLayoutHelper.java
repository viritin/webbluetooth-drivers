package in.virit.demo.views;

import in.virit.ble.LabelImage;

import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Renders product labels in different layouts depending on printer type.
 */
class LabelLayoutHelper {

    private static final String QR_URL = "https://vaadin.com/directory";

    /**
     * Layout for standard label printers (e.g. Phomemo M110).
     * QR code centered on top half, text below.
     */
    static void renderStandardLabel(LabelImage label, String productName, int weightGrams) {
        int w = label.getWidthPx();
        int h = label.getHeightPx();
        int margin = w / 15;

        // QR code centered in top half
        int topHalf = h / 2;
        int qrSize = topHalf - 2 * margin;
        int qrX = (w - qrSize) / 2;
        label.drawQrCode(QR_URL, qrX, margin, qrSize);

        // Text below QR
        Graphics2D g = label.getGraphics2D();
        g.setColor(Color.BLACK);
        int y = topHalf + margin / 2;

        // Product name
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        FontMetrics fm = g.getFontMetrics();
        int textX = (w - fm.stringWidth(productName)) / 2;
        g.drawString(productName, textX, y + fm.getAscent());
        y += fm.getHeight() + 2;

        // Weight + date on one line
        String info = (weightGrams > 0 ? weightGrams + "g  " : "") + todayString();
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
        fm = g.getFontMetrics();
        textX = (w - fm.stringWidth(info)) / 2;
        g.drawString(info, textX, y + fm.getAscent());
    }

    /**
     * Single-line layout for narrow tape printers (e.g. Dymo LetraTag 200B, 32px height).
     * QR code on the left, then product name, weight and date in one line.
     */
    static void renderTapeLabel(LabelImage label, String productName, int weightGrams) {
        int h = label.getHeightPx();
        int m = 1; // minimal margin for tape printers

        // QR code on the left, full height
        int qrSize = h - 2 * m;
        label.drawQrCode(QR_URL, m, m, qrSize);

        // Build single-line text: "Moose Fillet 590g 26.03.2026"
        StringBuilder text = new StringBuilder(productName);
        if (weightGrams > 0) {
            text.append("  ").append(weightGrams).append("g");
        }
        text.append("  ").append(todayString());

        Graphics2D g = label.getGraphics2D();
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        FontMetrics fm = g.getFontMetrics();
        int textX = m + qrSize + 3;
        int textY = (h + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text.toString(), textX, textY);
    }

    private static String todayString() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }
}
