package in.virit.dymo;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.server.StreamResource;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * A label image for Dymo LetraTag 200B that can be drawn on using Java's Graphics2D API.
 * The rendered image is displayed as a Vaadin Image component and
 * can be transferred to a Dymo LetraTag 200B label printer via Web Bluetooth.
 * <p>
 * Default size is 12mm height x variable width at 200 DPI (LetraTag 200B resolution).
 * The printable area is 32 pixels high (4 bytes per column).
 */
public class LetraTagLabelImage extends Image {

    private static final int DEFAULT_DPI = 200;
    private static final double MM_PER_INCH = 25.4;
    private static final int PRINTABLE_HEIGHT_PX = 32; // Full 32 pixels (4 bytes per column)

    private final int widthPx;
    private final int heightPx;
    private final BufferedImage bufferedImage;
    private final Graphics2D graphics;

    /**
     * Creates a label image with default width of 100mm.
     */
    public LetraTagLabelImage() {
        this(100); // 100mm width by default
    }

    /**
     * Creates a label image with the given width in millimeters.
     * Height is fixed at 12mm (printable area is 32 pixels).
     *
     * @param widthMm label width in millimeters
     */
    public LetraTagLabelImage(int widthMm) {
        this(widthMm, DEFAULT_DPI);
    }

    /**
     * Creates a label image with the given width and resolution.
     * Height is fixed at 12mm (printable area is 32 pixels).
     *
     * @param widthMm label width in millimeters
     * @param dpi printer resolution in dots per inch
     */
    public LetraTagLabelImage(int widthMm, int dpi) {
        this.widthPx = mmToPixels(widthMm, dpi);
        this.heightPx = PRINTABLE_HEIGHT_PX; // Fixed height for LetraTag 200B
        this.bufferedImage = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
        this.graphics = bufferedImage.createGraphics();

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        clear();
    }

    /**
     * Clears the label to white background.
     */
    public void clear() {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, widthPx, heightPx);
        graphics.setColor(Color.BLACK);
    }

    /**
     * Returns the Graphics2D context for custom drawing on this label.
     *
     * @return the Graphics2D context
     */
    public Graphics2D getGraphics2D() {
        return graphics;
    }

    /**
     * Returns the underlying BufferedImage.
     *
     * @return the buffered image
     */
    public BufferedImage getBufferedImage() {
        return bufferedImage;
    }

    /**
     * Returns a stretched version of the buffered image for LetraTag 200B.
     * The LetraTag 200B expects images to be stretched by 2x horizontally
     * to match the mobile app behavior.
     *
     * @return the stretched buffered image
     */
    public BufferedImage getStretchedBufferedImage() {
        // LetraTag 200B needs 2x horizontal stretching using nearest neighbor
        // This matches exactly what the mobile app does
        int stretchedWidth = widthPx * 2;
        BufferedImage stretched = new BufferedImage(stretchedWidth, heightPx, BufferedImage.TYPE_INT_ARGB);
        
        // Use nearest neighbor for pixel-perfect stretching
        Graphics2D g = stretched.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        
        // Stretch each pixel to 2x width
        for (int y = 0; y < heightPx; y++) {
            for (int x = 0; x < widthPx; x++) {
                int rgb = bufferedImage.getRGB(x, y);
                stretched.setRGB(x * 2, y, rgb);
                stretched.setRGB(x * 2 + 1, y, rgb);
            }
        }
        
        g.dispose();
        return stretched;
    }

    /**
     * Returns the label width in pixels.
     */
    public int getWidthPx() {
        return widthPx;
    }

    /**
     * Returns the label height in pixels (printable area).
     */
    public int getHeightPx() {
        return heightPx;
    }

    /**
     * Draws a QR code onto the label.
     *
     * @param content the text to encode in the QR code
     * @param x x position in pixels
     * @param y y position in pixels
     * @param size QR code size in pixels
     */
    public void drawQrCode(String content, int x, int y, int size) {
        try {
            var hints = java.util.Map.of(EncodeHintType.MARGIN, 0);
            BitMatrix bitMatrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            // Find actual QR module grid bounds (ZXing adds padding even with MARGIN=0)
            int minR = bitMatrix.getHeight(), maxR = 0, minC = bitMatrix.getWidth(), maxC = 0;
            for (int r = 0; r < bitMatrix.getHeight(); r++) {
                for (int c = 0; c < bitMatrix.getWidth(); c++) {
                    if (bitMatrix.get(c, r)) {
                        minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                        minC = Math.min(minC, c); maxC = Math.max(maxC, c);
                    }
                }
            }
            int modules = maxC - minC + 1; // QR module count (e.g. 21, 25, 29...)
            // Find largest integer pixel-per-module that fits in size
            int ppm = size / modules;
            if (ppm < 1) ppm = 1;
            int qrPx = ppm * modules;
            // Center within the requested size
            int offsetX = x + (size - qrPx) / 2;
            int offsetY = y + (size - qrPx) / 2;
            for (int row = 0; row < qrPx; row++) {
                int srcRow = minR + row / ppm;
                for (int col = 0; col < qrPx; col++) {
                    int srcCol = minC + col / ppm;
                    graphics.setColor(bitMatrix.get(srcCol, srcRow) ? Color.BLACK : Color.WHITE);
                    graphics.fillRect(offsetX + col, offsetY + row, 1, 1);
                }
            }
            graphics.setColor(Color.BLACK);
        } catch (WriterException e) {
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }

    /**
     * Draws text centered horizontally at the given y position.
     *
     * @param text the text to draw
     * @param y the y position (baseline) in pixels
     * @param fontSize the font size in pixels
     */
    public void drawCenteredText(String text, int y, int fontSize) {
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
        graphics.setFont(font);
        FontMetrics fm = graphics.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int x = (widthPx - textWidth) / 2;
        graphics.setColor(Color.BLACK);
        graphics.drawString(text, x, y);
    }

    /**
     * Refreshes the displayed image from the current BufferedImage state.
     * Call this after drawing operations to update the browser view.
     */
    public void refresh() {
        byte[] pngBytes = toPngBytes();
        StreamResource resource = new StreamResource("label.png",
                () -> new ByteArrayInputStream(pngBytes));
        resource.setContentType("image/png");
        setSrc(resource);
    }

    /**
     * Returns a trimmed and stretched version of the buffered image.
     * Trailing white columns are cropped before the 2x horizontal stretch.
     */
    public BufferedImage getTrimmedStretchedBufferedImage() {
        // Find rightmost non-white column
        int rightmost = 0;
        for (int x = widthPx - 1; x >= 0; x--) {
            for (int y = 0; y < heightPx; y++) {
                int rgb = bufferedImage.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r < 250 || g < 250 || b < 250) {
                    rightmost = x;
                    x = -1; // break outer
                    break;
                }
            }
        }
        int trimmedWidth = Math.max(rightmost + 1 + 4, 8); // small margin + minimum
        trimmedWidth = Math.min(trimmedWidth, widthPx);

        // 2x horizontal stretch using nearest neighbor
        int stretchedWidth = trimmedWidth * 2;
        BufferedImage stretched = new BufferedImage(stretchedWidth, heightPx, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < heightPx; y++) {
            for (int x = 0; x < trimmedWidth; x++) {
                int rgb = bufferedImage.getRGB(x, y);
                stretched.setRGB(x * 2, y, rgb);
                stretched.setRGB(x * 2 + 1, y, rgb);
            }
        }
        return stretched;
    }

    /**
     * Returns the label image as PNG bytes.
     *
     * @return PNG encoded bytes
     */
    public byte[] toPngBytes() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode label as PNG", e);
        }
    }

    private static int mmToPixels(int mm, int dpi) {
        return (int) Math.round(mm / MM_PER_INCH * dpi);
    }
}