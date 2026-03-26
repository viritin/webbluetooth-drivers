package in.virit.demo;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.router.Route;
import in.virit.ble.LabelPrinter;
import in.virit.dymo.DymoLetraTag200B;
import in.virit.phomemo.LabelImage;
import in.virit.phomemo.PhomemoPrinter;
import in.virit.wozinsky.WozinskyScale;

import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Route("")
public class LabelPrinterDemoView extends HorizontalLayout {

    private static final Map<String, List<String>> PRODUCTS = new LinkedHashMap<>();
    static {
        PRODUCTS.put("Deer", List.of("Fillet", "Roast", "Neck", "Minced"));
        PRODUCTS.put("Whitetail", List.of("Fillet", "Roast", "Neck", "Minced"));
        PRODUCTS.put("Moose", List.of("Fillet", "Roast", "Neck", "Minced"));
        PRODUCTS.put("Blueberry", List.of("Juice", "Whole"));
        PRODUCTS.put("Cowberry", List.of("Juice", "Whole"));
        PRODUCTS.put("Salmon", List.of("Whole", "Fillet"));
        PRODUCTS.put("Whitefish", List.of("Whole", "Fillet"));
        PRODUCTS.put("Herring", List.of("Whole", "Fillet"));
    }

    private final PhomemoPrinter phomemo = new PhomemoPrinter();
    private final DymoLetraTag200B dymo = new DymoLetraTag200B();
    private LabelPrinter activePrinter;

    private final WozinskyScale scale = new WozinskyScale();
    private final LabelImage labelPreview = new LabelImage();
    private final NumberField manualWeight = new NumberField("Weight (g)");
    private final Checkbox autoprint = new Checkbox("Autoprint", true);
    private final Button connectBtn = new Button("Connect Printer", VaadinIcon.PRINT.create());

    private Product selectedProduct;
    private Button selectedButton;

    public LabelPrinterDemoView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        var leftPanel = createProductSelector();
        leftPanel.setWidth("60%");

        var rightPanel = createRightPanel();
        rightPanel.setWidth("40%");

        add(leftPanel, rightPanel);
    }

    private VerticalLayout createProductSelector() {
        var layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(false);

        for (var entry : PRODUCTS.entrySet()) {
            String animal = entry.getKey();
            List<String> cuts = entry.getValue();

            var row = new FlexLayout();
            row.getStyle()
                    .set("gap", "0.5em")
                    .set("flex-wrap", "wrap")
                    .setMarginBottom("0.5em");

            for (String cut : cuts) {
                var btn = new Button(animal + "\n" + cut);
                btn.getStyle()
                        .setMinWidth("120px")
                        .setMinHeight("60px")
                        .set("white-space", "pre-line")
                        .set("font-size", "1em")
                        .set("flex", "1");
                btn.addClickListener(e -> selectProduct(animal, cut, btn));
                row.add(btn);
            }
            layout.add(row);
        }

        return layout;
    }

    private VerticalLayout createRightPanel() {
        var layout = new VerticalLayout();
        layout.setPadding(false);

        var printerSelect = new RadioButtonGroup<String>("Printer");
        printerSelect.setItems("Phomemo M110", "Dymo LetraTag 200B");
        printerSelect.setValue("Phomemo M110");
        activePrinter = phomemo;
        printerSelect.addValueChangeListener(e -> {
            activePrinter = "Dymo LetraTag 200B".equals(e.getValue()) ? dymo : phomemo;
            connectBtn.setEnabled(!activePrinter.isConnected());
        });

        connectBtn.addClickListener(e -> {
            if (activePrinter != null) {
                activePrinter.requestConnection();
            }
        });

        phomemo.addConnectionListener(connected ->
                connectBtn.setEnabled(!connected || activePrinter != phomemo));
        dymo.addConnectionListener(connected ->
                connectBtn.setEnabled(!connected || activePrinter != dymo));

        scale.addWeightListener(e -> {
            if (e.getWeightGrams() > 0) {
                manualWeight.setValue((double) e.getWeightGrams());
            }
            if (e.isStable() && e.getWeightGrams() > 0
                    && selectedProduct != null && autoprint.getValue()) {
                printLabel(e.getWeightGrams());
            }
        });

        manualWeight.setSuffixComponent(new Paragraph("g"));
        manualWeight.setStep(1);
        manualWeight.setMin(0);
        manualWeight.setWidthFull();

        var printBtn = new Button("Print", VaadinIcon.PRINT.create(), e -> {
            if (selectedProduct == null) {
                Notification.show("Select a product first");
                return;
            }
            Double val = manualWeight.getValue();
            if (val == null || val <= 0) {
                Notification.show("Enter weight");
                return;
            }
            printLabel(val.intValue());
        });
        printBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        printBtn.setWidthFull();

        labelPreview.getStyle()
                .setBorder("1px solid lightgray");

        layout.add(scale, printerSelect, connectBtn, autoprint, manualWeight, printBtn, labelPreview,
                (Component) phomemo, (Component) dymo);
        return layout;
    }

    private void selectProduct(String animal, String cut, Button btn) {
        if (selectedButton != null) {
            selectedButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
        }
        selectedProduct = new Product(animal, cut);
        selectedButton = btn;
        btn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        manualWeight.focus();
        updatePreview(0);
    }

    private void printLabel(int weightGrams) {
        if (selectedProduct == null || activePrinter == null) return;
        updatePreview(weightGrams);
        activePrinter.print(labelPreview.getTrimmedBufferedImage());
        Notification.show("Printing: " + selectedProduct.label() + " " + weightGrams + "g");
    }

    private void updatePreview(int weightGrams) {
        labelPreview.clear();
        if (selectedProduct == null) {
            labelPreview.refresh();
            return;
        }

        int w = labelPreview.getWidthPx();
        int h = labelPreview.getHeightPx();
        Graphics2D g = labelPreview.getGraphics2D();
        g.setColor(Color.BLACK);

        int marginX = w / 10;
        int marginY = h / 10;
        int y = marginY;

        // Product name
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(selectedProduct.label(), marginX, y + fm.getAscent());
        y += fm.getHeight() + 4;

        // Weight
        if (weightGrams > 0) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            fm = g.getFontMetrics();
            g.drawString(weightGrams + " g", marginX, y + fm.getAscent());
            y += fm.getHeight() + 4;
        }

        // Date
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        fm = g.getFontMetrics();
        g.drawString(date, marginX, y + fm.getAscent());

        labelPreview.refresh();
    }
}
