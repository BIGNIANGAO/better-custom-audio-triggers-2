package battleaudio.model;

/** Facts known at the point where a game operation actually completes. Empty fields mean unknown. */
public final class EventContext {
    public String sourceType = "";
    public String sourceId = "";
    public String targetType = "";
    public String targetId = "";
    public String enemyType = "";
    public Boolean lastEnemy;
    public String cardId = "";
    public String cardType = "";
    public String evokeCause = "";
    public int orbPosition;
    public int cardCost = Integer.MIN_VALUE;
    public Boolean upgraded;
    public Boolean exhaust;
    public Boolean temporary;
    public String sourcePile = "";
    public String targetPile = "";
    public String previousState = "";
    public String nextState = "";
    public int amount;
    public int before;
    public int after;
    public Object object;

    public EventContext amount(int value, int old, int next) { amount=value; before=old; after=next; return this; }
    public EventContext source(String type, String id) { sourceType=type; sourceId=id==null?"":id; return this; }
    public EventContext target(String type, String id, Object identity) {
        targetType=type; targetId=id==null?"":id; object=identity; return this;
    }
    public EventContext card(String id, String type, int cost, boolean isUpgraded, boolean isExhaust, Boolean isTemporary) {
        cardId=id==null?"":id; cardType=type==null?"":type; cardCost=cost;
        upgraded=isUpgraded; exhaust=isExhaust; temporary=isTemporary; return this;
    }
    public EventContext piles(String from, String to) { sourcePile=from==null?"":from; targetPile=to==null?"":to; return this; }
    public EventContext states(String old, String next) { previousState=old==null?"":old; nextState=next==null?"":next; return this; }
}
