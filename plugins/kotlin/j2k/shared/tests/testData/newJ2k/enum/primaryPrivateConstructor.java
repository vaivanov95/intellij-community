// IGNORE_K2
//class
package demo;

enum Color {
    ;
    private int code;

    private Color(int c) {
        code = c;
    }

    public int getCode() {
        return code;
    }
}