package in.virit.demo;

record Product(String animal, String cut) {

    String label() {
        return animal + " " + cut;
    }
}
