package in.virit.dymo;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;

import java.awt.*;

@Route("letratag")
public class LetraTagDemoView extends VerticalLayout {

    private final LetraTagLabelImage labelImage = new LetraTagLabelImage();
    private final DymoLetraTag200B printer = new DymoLetraTag200B();

    public LetraTagDemoView() {
        add(new H1("LetraTag 200B test app"));
        var qrContent = new TextField("QR Code content");
        qrContent.setWidthFull();
        qrContent.setValue("https://vaadin.com");

        var labelText = new TextArea("Label text");
        labelText.setWidthFull();
        labelText.setValue("Dymo LetraTag");

        var generateBtn = new Button("Generate Label", e ->
                generateLabel(qrContent.getValue(), labelText.getValue()));

        var connectBtn = new Button("Connect Printer", e -> printer.requestConnection());
        var printBtn = new Button("Print", e -> printer.print(labelImage.getTrimmedStretchedBufferedImage()));
        var testBtn = new Button("Test Pattern", e -> printer.printTestPattern());
        printer.addConnectionListener(connected -> {
            connectBtn.setVisible(!connected);
            printBtn.setEnabled(connected);
            testBtn.setEnabled(connected);
        });
        printBtn.setEnabled(false);
        testBtn.setEnabled(false);
        var printerButtons = new HorizontalLayout(connectBtn, printBtn, testBtn);

        labelImage.getStyle()
                .setBorder("1px solid lightgray");

        add(qrContent, labelText, generateBtn, printerButtons, labelImage, printer);

        generateLabel(qrContent.getValue(), labelText.getValue());
    }

    private void generateLabel(String qrContent, String text) {
        labelImage.clear();

        int w = labelImage.getWidthPx();
        int h = labelImage.getHeightPx();

        boolean hasQr = qrContent != null && !qrContent.isBlank();
        boolean hasText = text != null && !text.isBlank();

        int m = 1; // 1px margin to avoid printer clipping at physical edges
        if (hasQr && hasText) {
            int qrSize = h - 2 * m;
            labelImage.drawQrCode(qrContent, m, m, qrSize);

            int textX = m + qrSize + 2;
            Graphics2D g = labelImage.getGraphics2D();
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            FontMetrics fm = g.getFontMetrics();
            int textY = (h + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(text, textX, textY);
        } else if (hasQr) {
            int qrSize = h - 2 * m;
            labelImage.drawQrCode(qrContent, m, m, qrSize);
        } else if (hasText) {
            labelImage.drawCenteredText(text, h / 2 + 10, 28);
        }

        labelImage.refresh();
    }
}