package in.virit.rpi;

import in.virit.dymo.LetraTagProtocol;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Renders a plain text string onto a 32 px-tall LetraTag label image using
 * AWT (works headlessly with -Djava.awt.headless=true).
 */
final class TextLabelRenderer {

    private static final int LABEL_HEIGHT = LetraTagProtocol.PRINTABLE_HEIGHT_PX;
    private static final int FONT_SIZE = 26;
    private static final int LEFT_PADDING = 10;
    private static final int RIGHT_PADDING = 20;
    private static final int BASELINE_Y = 26;

    private TextLabelRenderer() {
    }

    static BufferedImage render(String text) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        pg.dispose();

        int width = Math.max(LEFT_PADDING + textWidth + RIGHT_PADDING, 64);
        BufferedImage img = new BufferedImage(width, LABEL_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, LABEL_HEIGHT);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(text, LEFT_PADDING, BASELINE_Y);
        g.dispose();
        return img;
    }
}
