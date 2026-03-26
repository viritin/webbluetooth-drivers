package in.virit.dymo;

import in.virit.ble.LabelImage;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * A label image for Dymo LetraTag 200B.
 * Height is fixed at 32 pixels (4 bytes per column in the printer protocol).
 * The LetraTag 200B requires 2x horizontal stretching for correct output.
 */
public class LetraTagLabelImage extends LabelImage {

    private static final int DEFAULT_DPI = 200;
    private static final int PRINTABLE_HEIGHT_PX = 32;

    public LetraTagLabelImage() {
        this(100);
    }

    public LetraTagLabelImage(int widthMm) {
        this(widthMm, DEFAULT_DPI);
    }

    public LetraTagLabelImage(int widthMm, int dpi) {
        super(mmToPixels(widthMm, dpi), PRINTABLE_HEIGHT_PX);
    }

    /**
     * Returns a 2x horizontally stretched version of the buffered image,
     * matching the mobile app behavior.
     */
    public BufferedImage getStretchedBufferedImage() {
        int w = getWidthPx();
        int h = getHeightPx();
        BufferedImage stretched = new BufferedImage(w * 2, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage src = getBufferedImage();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                stretched.setRGB(x * 2, y, rgb);
                stretched.setRGB(x * 2 + 1, y, rgb);
            }
        }
        return stretched;
    }

    /**
     * Returns a trimmed and 2x horizontally stretched version.
     * Trailing white columns are cropped before stretching.
     */
    public BufferedImage getTrimmedStretchedBufferedImage() {
        BufferedImage trimmed = getTrimmedBufferedImage();
        int tw = trimmed.getWidth();
        int th = trimmed.getHeight();
        BufferedImage stretched = new BufferedImage(tw * 2, th, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int rgb = trimmed.getRGB(x, y);
                stretched.setRGB(x * 2, y, rgb);
                stretched.setRGB(x * 2 + 1, y, rgb);
            }
        }
        return stretched;
    }
}
