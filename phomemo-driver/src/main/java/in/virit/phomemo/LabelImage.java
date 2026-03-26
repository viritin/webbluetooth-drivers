package in.virit.phomemo;

/**
 * A label image for Phomemo thermal printers.
 * Default size is 40mm x 30mm at 203 DPI.
 */
public class LabelImage extends in.virit.ble.LabelImage {

    private static final int DEFAULT_DPI = 203;

    public LabelImage() {
        this(40, 30);
    }

    public LabelImage(int widthMm, int heightMm) {
        super(widthMm, heightMm, DEFAULT_DPI);
    }

    public LabelImage(int widthMm, int heightMm, int dpi) {
        super(widthMm, heightMm, dpi);
    }
}
