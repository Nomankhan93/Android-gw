package com.bulktext.gateway;
import java.util.*;
/** Pure shared policy: used by Android and host tests. No transport side effects. */
public final class Policy {
    public static final int MAX_RECIPIENTS=5, MAX_SEGMENTS=15;
    private Policy() {}
    public static String normalize(String raw) {
        String n=raw.trim().replaceAll("[\\s()\\-]", "");
        if (n.matches("03[0-9]{9}")) n="+92"+n.substring(1);
        else if (n.matches("923[0-9]{9}")) n="+"+n;
        if (!n.matches("\\+[1-9][0-9]{7,14}")) throw new IllegalArgumentException("Use +country format or Pakistan 03XXXXXXXXX: " + raw);
        return n;
    }
    public static List<String> recipients(String raw) {
        Set<String> values=new LinkedHashSet<>();
        for(String row:raw.split("[\\n,;]+")) if(!row.trim().isEmpty()) values.add(normalize(row));
        if(values.isEmpty() || values.size()>MAX_RECIPIENTS) throw new IllegalArgumentException("Enter 1–5 unique authorized test numbers.");
        return new ArrayList<>(values);
    }
    public static String aggregate(List<String> parts, boolean unknown) {
        if(parts.isEmpty()) return "UNKNOWN";
        if(parts.stream().allMatch("DELIVERED"::equals)) return "DELIVERED";
        boolean pending=parts.contains("PENDING"), failed=parts.contains("FAILED");
        boolean success=parts.contains("SENT") || parts.contains("DELIVERED");
        if(pending) return unknown ? "UNKNOWN" : "SUBMITTING";
        if(failed) return success ? "PARTIAL" : "FAILED";
        return "SENT";
    }
    public static boolean claimable(String status) { return "QUEUED".equals(status); }
    public static String recovered(String status) { return "SUBMITTING".equals(status) ? "UNKNOWN" : status; }
    public static void checkBudget(int recipients,int segmentsEach) {
        if(recipients<1 || recipients>MAX_RECIPIENTS || segmentsEach<1 || (long)recipients*segmentsEach>MAX_SEGMENTS)
            throw new IllegalArgumentException("Prototype cap: 5 recipients and 15 total SMS segments per session.");
    }
}
