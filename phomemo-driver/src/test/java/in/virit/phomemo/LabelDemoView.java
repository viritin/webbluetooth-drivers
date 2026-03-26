package in.virit.phomemo;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;

import java.awt.*;

@Route("label")
public class LabelDemoView extends VerticalLayout {

    private final LabelImage labelImage = new LabelImage();
    private final PhomemoPrinter printer = new PhomemoPrinter();

    public LabelDemoView() {
        add(new H1("Simple test app to print to Phomeno M110 label printerNow  (Clas Ohlson branded version)"));
        var qrContent = new TextField("QR Code content");
        qrContent.setWidthFull();
        qrContent.setValue("https://vaadin.com");

        var labelText = new TextArea("Label text");
        labelText.setWidthFull();
        labelText.setValue("Vaadin");

        var generateBtn = new Button("Generate Label", e ->
                generateLabel(qrContent.getValue(), labelText.getValue()));

        var connectBtn = new Button("Connect Printer", e -> printer.requestConnection());
        var printBtn = new Button("Print", e -> printer.print(labelImage.getTrimmedBufferedImage()));
        printer.addConnectionListener(connected -> {
            connectBtn.setVisible(!connected);
            printBtn.setEnabled(connected);
        });
        printBtn.setEnabled(false);
        var printerButtons = new HorizontalLayout(connectBtn, printBtn);

        labelImage.getStyle()
                .setBorder("1px solid lightgray");

        add(qrContent, labelText, generateBtn, printerButtons, labelImage, printer);

        generateLabel(qrContent.getValue(), labelText.getValue());
    }

    private void generateLabel(String qrContent, String text) {
        labelImage.clear();

        int w = labelImage.getWidthPx();
        int h = labelImage.getHeightPx();
        int margin = 8;

        boolean hasQr = qrContent != null && !qrContent.isBlank();
        boolean hasText = text != null && !text.isBlank();

        if (hasQr && hasText) {
            // QR on the left, text on the right
            int qrSize = h - 2 * margin;
            labelImage.drawQrCode(qrContent, margin, margin, qrSize);

            int textX = margin + qrSize + margin;
            int textAreaWidth = w - textX - margin;
            Graphics2D g = labelImage.getGraphics2D();
            g.setColor(Color.BLACK);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 20);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            // Center text vertically in the available height
            int textY = (h + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(text, textX, textY);
        } else if (hasQr) {
            int qrSize = h - 2 * margin;
            int qrX = (w - qrSize) / 2;
            labelImage.drawQrCode(qrContent, qrX, margin, qrSize);
        } else if (hasText) {
            labelImage.drawCenteredText(text, h / 2, 32);
        }

        labelImage.refresh();
    }
}
