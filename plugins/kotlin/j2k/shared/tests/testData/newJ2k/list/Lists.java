// IGNORE_K2
import java.util.*;

public class Lists {
    public void test() {
        List<Object> xs = new ArrayList<Object>();
        List<Object> ys = new ELinkedList<Object>();
        ArrayList<Object> zs = new ArrayList<Object>();
        xs.add(null);
        ys.add(null);
        xs.clear();
        ys.clear();
        zs.add(null);
    }
}