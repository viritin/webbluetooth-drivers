package in.virit.demo.views;

record Product(String category, String species, String cut) {

    String label() {
        return species + " " + cut;
    }
}
