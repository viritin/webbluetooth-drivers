package in.virit.wozinsky;

import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.Route;

@Route("")
public class ScaleDemoView extends VerticalLayout {

    public ScaleDemoView() {
        var scale = new WozinskyScale();
        var logArea = new Pre();
        logArea.getStyle()
                .setBackground("#f4f4f4")
                .setPadding("1em")
                .setWidth("100%")
                .setMaxHeight("200px")
                .setOverflow(Style.Overflow.AUTO);
        logArea.getElement().getStyle().set("font-size", "0.85em");

        scale.addLogListener(e ->
                logArea.setText(logArea.getText() + e.getMessage() + "\n"));

        add(scale, logArea);
    }
}
