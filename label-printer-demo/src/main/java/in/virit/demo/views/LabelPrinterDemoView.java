package in.virit.demo.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.Route;
import in.virit.ble.LabelImage;
import in.virit.ble.UnifiedLabelPrinter;
import in.virit.ble.UnifiedLabelPrinter.PrinterType;
import in.virit.demo.views.ProductCatalog.Category;
import in.virit.demo.views.ProductCatalog.Species;
import in.virit.wozinsky.WozinskyScale;
import org.vaadin.firitin.layouts.HorizontalFloatLayout;

@Route("")
public class LabelPrinterDemoView extends HorizontalLayout {

    private final UnifiedLabelPrinter printer = new UnifiedLabelPrinter();
    private final ProductSelector productSelector = new ProductSelector();
    private final ControlPanel controlPanel = new ControlPanel();

    public LabelPrinterDemoView() {
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        productSelector.setWidth("60%");
        controlPanel.setWidth("40%");
        add(productSelector, controlPanel);
        productSelector.showCategories();
    }

    // =========================================================================
    // Product selector — drill-down from category → species → form
    // =========================================================================

    private class ProductSelector extends VerticalLayout {

        private Category selectedCategory;
        private Species selectedSpecies;
        private Product selectedProduct;

        ProductSelector() {
            setPadding(false);
            setSpacing(false);
        }

        Product getSelectedProduct() {
            return selectedProduct;
        }

        private void showCategories() {
            selectedCategory = null;
            selectedSpecies = null;
            selectedProduct = null;
            rebuild();

            var group = new RadioButtonGroup<Category>();
            group.setItems(ProductCatalog.CATEGORIES);
            group.setItemLabelGenerator(Category::name);
            group.addValueChangeListener(e -> {
                if (e.getValue() != null) showSpecies(e.getValue());
            });
            add(group);
            controlPanel.updatePreview(0);
        }

        private void showSpecies(Category category) {
            selectedCategory = category;
            selectedSpecies = null;
            selectedProduct = null;

            if (category.species().size() == 1) {
                showForms(category, category.species().getFirst());
                return;
            }

            rebuild();
            var group = new RadioButtonGroup<Species>();
            group.setItems(category.species());
            group.setItemLabelGenerator(Species::name);
            group.addValueChangeListener(e -> {
                if (e.getValue() != null) showForms(category, e.getValue());
            });
            add(group);
        }

        private void showForms(Category category, Species species) {
            selectedCategory = category;
            selectedSpecies = species;
            selectedProduct = null;
            rebuild();

            var group = new RadioButtonGroup<String>();
            group.setItems(species.forms());
            group.addValueChangeListener(e -> {
                if (e.getValue() != null) selectForm(category, species, e.getValue());
            });
            add(group);
        }

        private void selectForm(Category category, Species species, String form) {
            selectedProduct = new Product(category.name(), species.name(), form);
            controlPanel.onProductSelected();
        }

        private void rebuild() {
            removeAll();
            add(new Breadcrumb());
        }

        // ----- Breadcrumb -----

        private class Breadcrumb extends HorizontalFloatLayout {{
            setPadding(true);

            add(new BreadcrumbLink(VaadinIcon.HOME.create(), () -> showCategories()));

            if (selectedCategory != null) {
                var cat = selectedCategory;
                add(new Span(" / "), new BreadcrumbLink(cat.name(), () -> showSpecies(cat)));
            }
            if (selectedSpecies != null) {
                var cat = selectedCategory;
                var spec = selectedSpecies;
                add(new Span(" / "), new BreadcrumbLink(spec.name(), () -> showForms(cat, spec)));
            }
            if (selectedProduct != null) {
                var label = new Span(" / " + selectedProduct.cut());
                label.getStyle().setFontWeight(Style.FontWeight.BOLD);
                add(label);
            }
        }}

        private static class BreadcrumbLink extends Button {{
            getStyle().setFontSize("inherit");
        }
            BreadcrumbLink(com.vaadin.flow.component.icon.Icon icon, Runnable action) {
                super(icon);
                addClickListener(e -> action.run());
            }
            BreadcrumbLink(String text, Runnable action) {
                super(text);
                addClickListener(e -> action.run());
            }
        }
    }

    // =========================================================================
    // Control panel — printer, scale, weight input, print button, preview
    // =========================================================================

    private class ControlPanel extends VerticalLayout {

        private final WozinskyScale scale = new WozinskyScale();
        private final Button connectBtn = new Button("Connect Printer", VaadinIcon.PRINT.create());
        private final Span printerStatus = new Span();
        private final Checkbox autoprint = new Checkbox("Autoprint", true);
        private final NumberField weightField = new WeightField();
        private final Button printBtn = new PrintButton();
        private LabelImage labelPreview = createPreviewImage(null);

        ControlPanel() {
            setPadding(false);
            add(scale, connectBtn, printerStatus, autoprint, weightField, printBtn, labelPreview, printer);

            connectBtn.addClickListener(e -> printer.requestConnection());
            autoprint.setEnabled(false);
            printerStatus.setVisible(false);

            printer.addConnectionListener(this::onConnectionChanged);
            printer.addPrinterTypeListener(this::onPrinterTypeDetected);
            scale.addWeightListener(this::onWeightReceived);

        }

        void onProductSelected() {
            weightField.focus();
            updatePreview(0);
        }

        void updatePreview(int weightGrams) {
            labelPreview.clear();
            var product = productSelector.getSelectedProduct();
            if (product == null) {
                labelPreview.refresh();
                return;
            }

            if (printer.getPrinterType() == PrinterType.DYMO_LETRATAG) {
                LabelLayoutHelper.renderTapeLabel(labelPreview, product.label(), weightGrams);
            } else {
                LabelLayoutHelper.renderStandardLabel(labelPreview, product.label(), weightGrams);
            }
            labelPreview.refresh();
        }

        private void onConnectionChanged(boolean connected) {
            connectBtn.setVisible(!connected);
            printerStatus.setVisible(connected);
            autoprint.setEnabled(connected);
            printBtn.setEnabled(connected);
        }

        private void onPrinterTypeDetected(PrinterType type) {
            String name = type == PrinterType.PHOMEMO ? "Phomemo M110" : "Dymo LetraTag 200B";
            printerStatus.setText("Connected: " + name);

            var newPreview = createPreviewImage(type);
            replace(labelPreview, newPreview);
            labelPreview = newPreview;
            updatePreview(0);
        }

        private void onWeightReceived(WozinskyScale.WeightEvent e) {
            if (e.getWeightGrams() > 0) {
                weightField.setValue((double) e.getWeightGrams());
            }
            if (e.isStable() && e.getWeightGrams() > 0
                    && productSelector.getSelectedProduct() != null && autoprint.getValue()) {
                printLabel(e.getWeightGrams());
            }
        }

        private void printLabel(int weightGrams) {
            var product = productSelector.getSelectedProduct();
            if (product == null || !printer.isConnected()) return;

            updatePreview(weightGrams);
            if (printer.getPrinterType() == PrinterType.DYMO_LETRATAG) {
                printer.print(labelPreview.getTrimmedBufferedImage());
            } else {
                printer.print(labelPreview.getBufferedImage());
            }
            Notification.show("Printing: " + product.label() + " " + weightGrams + "g");
        }

        private LabelImage createPreviewImage(PrinterType type) {
            LabelImage preview;
            if (type == PrinterType.DYMO_LETRATAG) {
                preview = new LabelImage(LabelImage.mmToPixels(100, 200), 32);
            } else {
                preview = new LabelImage(40, 30, 203);
            }
            preview.getStyle().setBorder("1px solid lightgray");
            return preview;
        }

        // ----- Inner components -----

        private class WeightField extends NumberField {{
            setLabel("Weight (g)");
            setSuffixComponent(new Paragraph("g"));
            setStep(1);
            setMin(0);
            setWidthFull();
        }}

        private class PrintButton extends Button {{
            setText("Print");
            setIcon(VaadinIcon.PRINT.create());
            addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            setWidthFull();
            setEnabled(false);
            addClickListener(e -> {
                if (productSelector.getSelectedProduct() == null) {
                    Notification.show("Select a product first");
                    return;
                }
                Double val = weightField.getValue();
                if (val == null || val <= 0) {
                    Notification.show("Enter weight");
                    return;
                }
                printLabel(val.intValue());
            });
        }}
    }
}
