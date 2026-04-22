package in.virit.dymo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Dymo LetraTag 200B wire protocol encoder. Pure Java, no UI or framework
 * dependencies so it can be used both from the Vaadin component and from a
 * headless Raspberry Pi bridge.
 */
public final class LetraTagProtocol {

    /** LetraTag 200B bitmap height in pixels (4 bytes per column). */
    public static final int PRINTABLE_HEIGHT_PX = 32;

    /** Payload chunk size the vendor app uses on the wire. */
    public static final int CHUNK_SIZE = 500;

    private LetraTagProtocol() {
    }

    /**
     * Doubles the image horizontally. The LetraTag prints roughly half-width
     * pixels, so the vendor app and this driver compensate by duplicating
     * every column.
     */
    public static BufferedImage stretchHorizontally(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage stretched = new BufferedImage(w * 2, h, BufferedImage.TYPE_INT_ARGB);
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
     * Encodes the image as a full LetraTag transfer: 9-byte framing header
     * followed by the payload (start, print-data, form-feed, status, end).
     * The caller is responsible for splitting the returned byte array into
     * the header (first 9 bytes) and chunked payload as the BLE protocol
     * requires — see {@link #CHUNK_SIZE}.
     */
    public static byte[] buildPrintCommands(BufferedImage image) {
        int width = image.getWidth();
        int printableHeight = Math.min(image.getHeight(), PRINTABLE_HEIGHT_PX);

        byte[] bitmap = new byte[width * 4];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < printableHeight; y++) {
                if (isBlackPixel(image, x, y)) {
                    int xOffset = x * 4;
                    int yOffset = 3 - (y / 8);
                    int bitPosition = 7 - (y % 8);
                    bitmap[xOffset + yOffset] |= (byte) (1 << bitPosition);
                }
            }
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        // START
        payload.write(0x1B); payload.write(0x73);
        payload.write(0x9A); payload.write(0x02); payload.write(0x00); payload.write(0x00);
        // PRINT_DATA
        payload.write(0x1B); payload.write(0x44);
        payload.write(0x01); // bits per pixel
        payload.write(0x02); // alignment
        payload.write(width & 0xFF);
        payload.write((width >> 8) & 0xFF);
        payload.write((width >> 16) & 0xFF);
        payload.write((width >> 24) & 0xFF);
        payload.write(0x20); payload.write(0x00); payload.write(0x00); payload.write(0x00);
        payload.writeBytes(bitmap);
        // FORM_FEED, STATUS, END
        payload.write(0x1B); payload.write(0x45);
        payload.write(0x1B); payload.write(0x41);
        payload.write(0x1B); payload.write(0x51);

        int payloadLength = payload.size();
        byte[] header = new byte[9];
        header[0] = (byte) 0xFF;
        header[1] = (byte) 0xF0;
        header[2] = 0x12;
        header[3] = 0x34;
        header[4] = (byte) (payloadLength & 0xFF);
        header[5] = (byte) ((payloadLength >> 8) & 0xFF);
        header[6] = (byte) ((payloadLength >> 16) & 0xFF);
        header[7] = (byte) ((payloadLength >> 24) & 0xFF);
        int checksum = 0;
        for (int i = 0; i < 8; i++) {
            checksum += header[i] & 0xFF;
        }
        header[8] = (byte) (checksum & 0xFF);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(payload.toByteArray());
        return out.toByteArray();
    }

    private static boolean isBlackPixel(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int gray = (r * 299 + g * 587 + b * 114) / 1000;
        return gray < 128;
    }
}
