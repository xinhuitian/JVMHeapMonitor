import com.sun.tools.attach.AttachNotSupportedException;

import javax.management.*;
import javax.management.remote.*;
import java.io.IOException;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

import static java.lang.management.ManagementFactory.*;

/**
 * Created by root on 5/4/15.
 */
public class MonitorClient {

    private LocalVM lvm = null;
    private JMXServiceURL jmxUrl = null;
    private JMXConnector jmxc = null;
    private MBeanServerConnection mbsc = null;
    private SnapshotMBeanServerConnection server = null;

    private RuntimeMXBean         runtimeMBean = null;
    private MemoryMXBean          memoryMBean = null;

    private boolean hasPlatformMXBeans = false;
    private boolean hasHotSpotDiagnosticMXBean= false;
    private boolean hasCompilationMXBean = false;
    private boolean supportsLockUsage = false;

    final static private String HOTSPOT_DIAGNOSTIC_MXBEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";

    public MonitorClient(LocalVM lvm) {
        this.lvm = lvm;
    }

    public void tryConnect() throws IOException, AttachNotSupportedException {
        //if (jmxUrl == null) { System.out.println("jmxUrl is null!");}

        lvm.loadAgent();
        if (this.jmxUrl == null) {
            this.jmxUrl = new JMXServiceURL(lvm.getAddress());
        }
        this.jmxc = JMXConnectorFactory.connect(jmxUrl);
        this.mbsc = jmxc.getMBeanServerConnection();
        this.server = Snapshot.newSnapshot(mbsc);

        //System.out.println(server.getMBeanCount());

        try {
            ObjectName on = new ObjectName(THREAD_MXBEAN_NAME);
            if (on == null)
                System.out.println("On is null");
            this.hasPlatformMXBeans = server.isRegistered(on);
            this.hasHotSpotDiagnosticMXBean =
                    server.isRegistered(new ObjectName(HOTSPOT_DIAGNOSTIC_MXBEAN_NAME));
            if (this.hasPlatformMXBeans) {
                MBeanOperationInfo[] mopis = server.getMBeanInfo(on).getOperations();
                // look for findDeadlockedThreads operations;
                for (MBeanOperationInfo op : mopis) {
                    if (op.getName().equals("findDeadlockedThreads")) {
                        this.supportsLockUsage = true;
                        break;
                    }
                }

                on = new ObjectName(COMPILATION_MXBEAN_NAME);
                this.hasCompilationMXBean = server.isRegistered(on);
            }
        } catch (MalformedObjectNameException e) {
            // should not reach here
            throw new InternalError(e.getMessage());
        } catch (IntrospectionException e) {
            InternalError ie = new InternalError(e.getMessage());
            ie.initCause(e);
            throw ie;
        } catch (InstanceNotFoundException e) {
            InternalError ie = new InternalError(e.getMessage());
            ie.initCause(e);
            throw ie;
        } catch (ReflectionException e) {
            InternalError ie = new InternalError(e.getMessage());
            ie.initCause(e);
            throw ie;
        }

        if (hasPlatformMXBeans) getRuntimeMXBean();

        //System.out.println(getRuntimeMXBean().getName());
    }

    public void flush() {
        if (server != null) {
            server.flush();
        }
    }

    public synchronized RuntimeMXBean getRuntimeMXBean() throws IOException {
        if (hasPlatformMXBeans && runtimeMBean == null) {
            runtimeMBean =
                    newPlatformMXBeanProxy(server, RUNTIME_MXBEAN_NAME,
                            RuntimeMXBean.class);
        }
        return runtimeMBean;
    }

    public synchronized MemoryMXBean getMemoryMXBean() throws IOException {
        if (hasPlatformMXBeans && memoryMBean == null) {
            memoryMBean =
                    newPlatformMXBeanProxy(server, MEMORY_MXBEAN_NAME,
                            MemoryMXBean.class);
        }
        return memoryMBean;
    }

    public Map<ObjectName, MBeanInfo> getMBeans(String domain) throws IOException {
        ObjectName name = null;
        if (domain != null) {
            try {
                name = new ObjectName(domain + ":*");
            } catch (MalformedObjectNameException e) {
                assert(false);
            }
        }

        Set mbeans = server.queryNames(name, null);
        Map<ObjectName, MBeanInfo> result =
                new HashMap<ObjectName, MBeanInfo>(mbeans.size());
        Iterator iterator = mbeans.iterator();
        while (iterator.hasNext()) {
            Object object = iterator.next();
            if (object instanceof ObjectName) {
                ObjectName o = (ObjectName)object;
                try {
                    MBeanInfo info = server.getMBeanInfo(o);
                    result.put(o, info);
                } catch (IntrospectionException e) {
                    // TODO: should log the error
                } catch (InstanceNotFoundException e) {
                    // TODO: should log the error
                } catch (ReflectionException e) {
                    // TODO: should log the error
                }
            }
        }
        return result;
    }

    public AttributeList getAttributes(ObjectName name, String[] attributes)
            throws IOException {
        AttributeList list = null;
        try {
            list = server.getAttributes(name, attributes);
        } catch (InstanceNotFoundException e) {
            // TODO: A MBean may have been unregistered.
            // need to set up listener to listen for MBeanServerNotification.
        } catch (ReflectionException e) {
            // TODO: should log the error
        }
        return list;
    }

    public interface SnapshotMBeanServerConnection
            extends MBeanServerConnection {
        /**
         * Flush all cached values of attributes.
         */
        public void flush();
    }

    public static class Snapshot {
        private Snapshot() {
        }
        public static SnapshotMBeanServerConnection
        newSnapshot(MBeanServerConnection mbsc) {
            final InvocationHandler ih = new SnapshotInvocationHandler(mbsc);
            return (SnapshotMBeanServerConnection) Proxy.newProxyInstance(
                    Snapshot.class.getClassLoader(),
                    new Class[]{SnapshotMBeanServerConnection.class},
                    ih);
        }
    }

    static class SnapshotInvocationHandler implements InvocationHandler {

        private final MBeanServerConnection conn;
        private Map<ObjectName, NameValueMap> cachedValues = newMap();
        private Map<ObjectName, Set<String>> cachedNames = newMap();

        @SuppressWarnings("serial")
        private static final class NameValueMap
                extends HashMap<String, Object> {}

        SnapshotInvocationHandler(MBeanServerConnection conn) {
            this.conn = conn;
        }

        synchronized void flush() {
            cachedValues = newMap();
        }

        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            final String methodName = method.getName();
            if (methodName.equals("getAttribute")) {
                return getAttribute((ObjectName) args[0], (String) args[1]);
            } else if (methodName.equals("getAttributes")) {
                return getAttributes((ObjectName) args[0], (String[]) args[1]);
            } else if (methodName.equals("flush")) {
                flush();
                return null;
            } else {
                try {
                    return method.invoke(conn, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }

        private Object getAttribute(ObjectName objName, String attrName)
                throws MBeanException, InstanceNotFoundException,
                AttributeNotFoundException, ReflectionException, IOException {
            final NameValueMap values = getCachedAttributes(
                    objName, Collections.singleton(attrName));
            Object value = values.get(attrName);
            if (value != null || values.containsKey(attrName)) {
                return value;
            }
            // Not in cache, presumably because it was omitted from the
            // getAttributes result because of an exception.  Following
            // call will probably provoke the same exception.
            return conn.getAttribute(objName, attrName);
        }

        private AttributeList getAttributes(
                ObjectName objName, String[] attrNames) throws
                InstanceNotFoundException, ReflectionException, IOException {
            final NameValueMap values = getCachedAttributes(
                    objName,
                    new TreeSet<String>(Arrays.asList(attrNames)));
            final AttributeList list = new AttributeList();
            for (String attrName : attrNames) {
                final Object value = values.get(attrName);
                if (value != null || values.containsKey(attrName)) {
                    list.add(new Attribute(attrName, value));
                }
            }
            return list;
        }

        private synchronized NameValueMap getCachedAttributes(
                ObjectName objName, Set<String> attrNames) throws
                InstanceNotFoundException, ReflectionException, IOException {
            NameValueMap values = cachedValues.get(objName);
            if (values != null && values.keySet().containsAll(attrNames)) {
                return values;
            }
            attrNames = new TreeSet<String>(attrNames);
            Set<String> oldNames = cachedNames.get(objName);
            if (oldNames != null) {
                attrNames.addAll(oldNames);
            }
            values = new NameValueMap();
            final AttributeList attrs = conn.getAttributes(
                    objName,
                    attrNames.toArray(new String[attrNames.size()]));
            for (Attribute attr : attrs.asList()) {
                values.put(attr.getName(), attr.getValue());
            }
            cachedValues.put(objName, values);
            cachedNames.put(objName, attrNames);
            return values;
        }

        // See http://www.artima.com/weblogs/viewpost.jsp?thread=79394
        private static <K, V> Map<K, V> newMap() {
            return new HashMap<K, V>();
        }
    }
}
