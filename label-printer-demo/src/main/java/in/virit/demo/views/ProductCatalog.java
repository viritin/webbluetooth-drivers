package in.virit.demo.views;

import java.util.List;

/**
 * Product catalog for a Nordic hunter-gatherer household.
 * Structure: Category → Species/Item → Cuts/Forms
 */
class ProductCatalog {

    record Category(String name, List<Species> species) {}
    record Species(String name, List<String> forms) {}

    static final List<Category> CATEGORIES = List.of(
            new Category("Game", List.of(
                    new Species("Moose", List.of("Fillet", "Roast", "Neck", "Minced", "Ribs", "Liver", "Heart", "Tongue")),
                    new Species("Deer", List.of("Fillet", "Roast", "Neck", "Minced", "Ribs")),
                    new Species("Whitetail", List.of("Fillet", "Roast", "Neck", "Minced")),
                    new Species("Wild Boar", List.of("Fillet", "Roast", "Minced", "Ribs")),
                    new Species("Hare", List.of("Whole", "Legs", "Saddle")),
                    new Species("Grouse", List.of("Whole", "Breast")),
                    new Species("Mallard", List.of("Whole", "Breast")),
                    new Species("Goat", List.of("Tomahawk", "Tenderloin", "Striploin")),
                    new Species("Pheasant", List.of("Whole", "Breast"))
            )),
            new Category("Fish", List.of(
                    new Species("Salmon", List.of("Whole", "Fillet", "Smoked", "Gravlax")),
                    new Species("Trout", List.of("Whole", "Fillet", "Smoked")),
                    new Species("Whitefish", List.of("Whole", "Fillet", "Smoked", "Roe")),
                    new Species("Pike", List.of("Whole", "Fillet", "Minced")),
                    new Species("Perch", List.of("Whole", "Fillet")),
                    new Species("Vendace", List.of("Whole", "Roe")),
                    new Species("Herring", List.of("Whole", "Fillet", "Pickled"))
            )),
            new Category("Berries", List.of(
                    new Species("Blueberry", List.of("Whole", "Juice", "Jam")),
                    new Species("Lingonberry", List.of("Whole", "Juice", "Jam", "Crushed")),
                    new Species("Cloudberry", List.of("Whole", "Jam")),
                    new Species("Cranberry", List.of("Whole", "Juice")),
                    new Species("Sea Buckthorn", List.of("Whole", "Juice")),
                    new Species("Rowanberry", List.of("Whole", "Jelly"))
            )),
            new Category("Mushrooms", List.of(
                    new Species("Chanterelle", List.of("Fresh", "Dried", "Sautéed")),
                    new Species("Porcini", List.of("Fresh", "Dried", "Sliced")),
                    new Species("Funnel Ch.", List.of("Fresh", "Dried")),
                    new Species("Milk Cap", List.of("Fresh", "Salted"))
            )),
            new Category("Vegetables", List.of(
                    new Species("Potato", List.of("Whole")),
                    new Species("Carrot", List.of("Whole", "Sliced")),
                    new Species("Beet", List.of("Whole", "Pickled")),
                    new Species("Peas", List.of("Shelled")),
                    new Species("Broad Bean", List.of("Shelled"))
            ))
    );
}
