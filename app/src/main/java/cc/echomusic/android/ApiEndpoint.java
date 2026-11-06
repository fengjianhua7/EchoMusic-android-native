package cc.echomusic.android;

/** Stable account namespace; the private transport port changes independently across launches. */
public final class ApiEndpoint {
    public static final String LOCAL="embedded://echomusic";
    private ApiEndpoint(){}
    public static String selected(String configured){String value=configured==null?"":configured.trim().replaceAll("/+$","");return value.isEmpty()?LOCAL:value;}
    public static boolean embedded(String endpoint){return LOCAL.equals(selected(endpoint));}
}
