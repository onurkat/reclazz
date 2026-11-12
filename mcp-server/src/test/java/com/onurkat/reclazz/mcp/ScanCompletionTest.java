package com.onurkat.reclazz.mcp;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScanCompletionTest {
    static void advertise(BufferedReader in,PrintWriter out) throws Exception {
        String doctor=in.readLine();assertTrue(doctor.startsWith("DOCTOR "));
        JsonObject evidence=DoctorTest.evidence(doctor);evidence.addProperty("scanCompletionSupported",true);DoctorTest.send(out,doctor,evidence);
    }
    static JsonObject receipt(String command,int count) {
        assertTrue(command.startsWith("SCAN request="),command);String token=command.substring(13);
        JsonObject r=new JsonObject();r.addProperty("requestId",token);r.addProperty("sessionId","evidence");r.addProperty("status","queued");
        r.addProperty("detail","Scan events delivered; reload is separate");r.addProperty("enqueued",count);r.addProperty("reloadConfirmed",false);return r;
    }
    static void send(PrintWriter out,String command,JsonObject r) {
        JsonObject e=new JsonObject();e.addProperty("level","INFO");e.addProperty("message","SCAN_RESULT "+command.substring(13)+" "+r);out.println(e);
    }
    @Test void zeroAndPositiveCountsRequireMatchingReceipt() throws Exception {
        for(int count:List.of(0,3))try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{advertise(in,out);String scan=in.readLine();send(out,scan,receipt(scan,count));})) {
            try(var session=BuildSession.open(agent.opts())){var r=session.scan();assertEquals("queued",r.get("status").getAsString());assertEquals(count,r.get("enqueued").getAsInt());assertFalse(r.get("reloadConfirmed").getAsBoolean());}
            agent.verify();
        }
    }
    @Test void unknownLateTokenIsIgnoredUntilThisRequestCompletes() throws Exception {
        try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{advertise(in,out);String scan=in.readLine();send(out,"SCAN request=old",receipt("SCAN request=old",99));send(out,scan,receipt(scan,2));})) {
            try(var session=BuildSession.open(agent.opts())){assertEquals(2,session.scan().get("enqueued").getAsInt());}agent.verify();
        }
    }
    @Test void wrongIdentityAndMalformedCompletionNeverSucceed() throws Exception {
        for(String mode:List.of("token","session","negative","fraction","string","missing","status","confirmed","invalid")) {
            try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{advertise(in,out);String scan=in.readLine();var r=receipt(scan,1);
                switch(mode){case "token"->r.addProperty("requestId","wrong");case "session"->r.addProperty("sessionId","other");
                    case "negative"->r.addProperty("enqueued",-1);case "fraction"->r.addProperty("enqueued",1.5);case "string"->r.addProperty("enqueued","1");
                    case "missing"->r.remove("enqueued");case "status"->r.addProperty("status","unavailable");case "confirmed"->r.addProperty("reloadConfirmed",true);
                    case "invalid"->{JsonObject e=new JsonObject();e.addProperty("level","INFO");e.addProperty("message","SCAN_RESULT "+scan.substring(13)+" broken");out.println(e);return;}}
                send(out,scan,r);
            })) {try(var session=BuildSession.open(agent.opts())){assertThrows(IOException.class,session::scan,mode);}agent.verify();}
        }
    }
    @Test void withheldOrDisconnectedReplyIsNotDispatchSuccess() throws Exception {
        for(boolean hold:List.of(true,false))try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{advertise(in,out);assertTrue(in.readLine().startsWith("SCAN request="));if(hold)assertNull(in.readLine());})) {
            var opts=agent.opts();opts.put("timeoutMs","150");try(var session=BuildSession.open(opts)){assertThrows(IOException.class,session::scan);}agent.verify();
        }
    }
    @Test void oldOrFalseCapabilityDoesNotSendAnUnsupportedMutation() throws Exception {
        for(String mode:List.of("missing","false","invalid"))try(var agent=new BuildSafetyTest.FakeAgent((in,out)->{
            String doctor=in.readLine();var evidence=DoctorTest.evidence(doctor);
            if(mode.equals("false"))evidence.addProperty("scanCompletionSupported",false);
            if(mode.equals("invalid"))evidence.addProperty("scanCompletionSupported","true");
            DoctorTest.send(out,doctor,evidence);assertNull(in.readLine());
        })) {try(var session=BuildSession.open(agent.opts())){assertThrows(IOException.class,session::scan);}agent.verify();}
    }
}
