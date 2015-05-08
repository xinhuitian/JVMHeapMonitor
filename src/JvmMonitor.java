import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanInfo;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.File;
import java.io.IOException;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by root on 4/29/15.
 */
public class JvmMonitor {


    public static void main(String[] args) throws AttachNotSupportedException, IOException, AgentLoadException, AgentInitializationException, InterruptedException {
        //System.out.println("Start!");
        if (args.length < 1) {
            System.out.println("Usage: JvmMonitor + pid");
            return;
        }

        LocalVM lvm = new LocalVM(args[0]);
        final MonitorClient mc = new MonitorClient(lvm);
        mc.tryConnect();
        final MemoryMXBean mbean = mc.getMemoryMXBean();
        int begin = 0;
        while (true) {
            begin = 0;

            Map<ObjectName, MBeanInfo> mBeanMap = mc.getMBeans("java.lang");
            Set<ObjectName> keys = mBeanMap.keySet();
            ObjectName[] objectNames = keys.toArray(new ObjectName[keys.size()]);
            for (ObjectName objectName : objectNames) {
                String type = objectName.getKeyProperty("type");
                if (begin == 0) {
                    long startTime = System.currentTimeMillis();
                    System.out.print(startTime + " ");
                    begin = 1;
                }

                if (type.equals("MemoryPool")) {

                    MemoryUsage mu;

                    AttributeList al =
                            mc.getAttributes(objectName,
                                    new String[]{"Usage", "UsageThreshold"});
                    if (al.size() > 0) {
                        if (MemoryType.HEAP.name().equals(((Attribute) mc.getAttributes(objectName,
                                new String[]{"Type"}).get(0)).getValue())) {
                            //System.out.println(objectName.getCanonicalKeyPropertyListString());
                            CompositeData cd = (CompositeData) ((Attribute) al.get(0)).getValue();
                            //System.out.println(cd.toString());
                            mu = MemoryUsage.from(cd);

                            System.out.printf("%2.2f ", (double) mu.getUsed() / (double) mu.getMax());
                        }
                    }
                }
            }
            System.out.printf("\n");
            mc.flush();
            TimeUnit.SECONDS.sleep(4);
        }
        /*while (true) {
            System.out.println(mbean.getHeapMemoryUsage());
            mc.flush();
            Thread.sleep(1000);
        }
*/        //lvm.loadAgent("3363");
        /*TimerTask timerTask = new TimerTask() {
            public void run() {
                System.out.printf("%2.2f\n", (double)mbean.getHeapMemoryUsage().getUsed() / (double)mbean.getHeapMemoryUsage().getMax() );
                mc.flush();
            }
        };

        String timerName = "Timer-1";
        Timer timer = new Timer(timerName, false);
        timer.schedule(timerTask, 0, 4000);*/
    }
}
