package fi.iki.aeirola.teddyclientlib.models.request;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by Axel on 18.11.2014.
 */
public class LineRequest implements Serializable {
    public Map<Long, Get> get;
    public Sub sub_add;
    public Sub sub_rm;

    public boolean expectResponse() {
        return get != null;
    }

    public static class Get {
        public int lv = 1;
        public int count = 10;
        public Long afterLine;
        public Long beforeLine;
        public boolean text = true;
    }

    public static class Sub {
        public Subscription add;
    }

    public static class Subscription {
        public final List<Long> view = new ArrayList<>();
        public int lv = 1;
        public boolean text = true;
    }
}
