import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

/**
 * Created by root on 5/4/15.
 */
public class LocalVM {

    public LocalVM(String vmid) {
        this.vmid = vmid;
    }

    private static final String LOCAL_CONNECTOR_ADDRESS_PROP =
            "com.sun.management.jmxremote.localConnectorAddress";

    private String address = null;
    private String vmid = null;
    public String getAddress() { return address; }

    public void loadAgent() throws IOException, AttachNotSupportedException {
        if (this.vmid == null) {
            System.out.println("vmid is null!");
            return;
        }

        loadAgent(this.vmid);
    }

    public void loadAgent(String vmid) throws IOException, AttachNotSupportedException {
        VirtualMachine vm = VirtualMachine.attach(vmid);

        String javaHome = vm.getSystemProperties().getProperty("java.home");
        //System.out.println(javaHome);
        //String agent = javaHome
        //        + File.separator + "lib" + File.separator
        //        + "management-agent.jar";

        String agent = javaHome + File.separator + "jre" + File.separator +
                "lib" + File.separator + "management-agent.jar";

        File f = new File(agent);

        if (!f.exists()) {
            agent = javaHome + File.separator +  "lib" + File.separator +
                    "management-agent.jar";
            f = new File(agent);
            if (!f.exists()) {
                throw new IOException("Management agent not found");
            }
        }

        agent = f.getCanonicalPath();

        //vm.loadAgent("/opt/spark-analysis/java/Agent.jar");

        try {
            vm.loadAgent(agent, "com.sun.management.jmxremote");
        } catch (AgentLoadException x) {
            IOException ioe = new IOException(x.getMessage());
            ioe.initCause(x);
            throw ioe;
        } catch (AgentInitializationException x) {
            IOException ioe = new IOException(x.getMessage());
            ioe.initCause(x);
            throw ioe;
        }

        Properties agentProps = vm.getAgentProperties();
        this.address = (String) agentProps.get(LOCAL_CONNECTOR_ADDRESS_PROP);

        //System.out.println(address);
        //Thread.sleep(10000);
        vm.detach();
    }
}
