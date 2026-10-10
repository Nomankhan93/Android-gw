import com.bulktext.gateway.Policy;
import java.util.*;
public class PolicyTest {
 static int count=0;
 static void eq(Object a,Object b) {count++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
 static void rejects(Runnable f){count++;try{f.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected rejection");}
 public static void main(String[] args){
  eq(Policy.normalize("03001234567"),"+923001234567");
  eq(Policy.normalize("923001234567"),"+923001234567");
  eq(Policy.normalize("+44 7700-900123"),"+447700900123");
  eq(Policy.recipients("03001234567\n+923001234567").size(),1);
  rejects(()->Policy.normalize("3.00123E9"));rejects(()->Policy.normalize("3001234567"));
  rejects(()->Policy.normalize("+0123456789")); rejects(()->Policy.recipients("\n"));
  rejects(()->Policy.recipients("+923001234560,+923001234561,+923001234562,+923001234563,+923001234564,+923001234565"));
  Policy.checkBudget(5,3);count++;rejects(()->Policy.checkBudget(5,4));
  eq(Policy.aggregate(List.of("SENT","SENT"),false),"SENT");
  eq(Policy.aggregate(List.of("SENT","FAILED"),false),"PARTIAL");
  eq(Policy.aggregate(List.of("FAILED","FAILED"),false),"FAILED");
  eq(Policy.aggregate(List.of("SENT","PENDING"),true),"UNKNOWN");
  eq(Policy.aggregate(List.of("SENT","PENDING"),false),"SUBMITTING");
  eq(Policy.aggregate(List.of("DELIVERED","SENT"),false),"SENT");
  eq(Policy.aggregate(List.of("DELIVERED","DELIVERED"),true),"DELIVERED");
  eq(Policy.recovered("SUBMITTING"),"UNKNOWN");eq(Policy.recovered("SENT"),"SENT");
  for(String s:List.of("UNKNOWN","FAILED","PARTIAL","SENT","DELIVERED","CANCELLED","SUBMITTING"))eq(Policy.claimable(s),false);
  eq(Policy.claimable("QUEUED"),true);
  System.out.println("PASS: "+count+" policy assertions");
 }
}
