/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.boot;

import org.redkale.cluster.ClusterAgent;
import org.redkale.util.RedkaleClassLoader;
import org.redkale.net.TransportGroupInfo;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import javax.annotation.Resource;
import javax.net.ssl.SSLContext;
import javax.xml.parsers.*;
import org.redkale.boot.ClassFilter.FilterEntry;
import org.redkale.cluster.*;
import org.redkale.convert.Convert;
import org.redkale.convert.bson.BsonFactory;
import org.redkale.convert.json.*;
import org.redkale.mq.*;
import org.redkale.net.*;
import org.redkale.net.http.*;
import org.redkale.net.sncp.*;
import org.redkale.service.Service;
import org.redkale.source.*;
import org.redkale.util.AnyValue.DefaultAnyValue;
import org.redkale.util.*;
import org.redkale.watch.*;
import org.w3c.dom.*;

/**
 *
 * 进程启动类，全局对象。  <br>
 * <pre>
 * 程序启动执行步骤:
 *     1、读取application.xml
 *     2、进行classpath扫描动态加载Service、WebSocket与Servlet
 *     3、优先加载所有SNCP协议的服务，再加载其他协议服务， 最后加载WATCH协议的服务
 *     4、最后进行Service、Servlet与其他资源之间的依赖注入
 * </pre>
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public final class Application {

    /**
     * 当前进程启动的时间， 类型： long
     */
    public static final String RESNAME_APP_TIME = "APP_TIME";

    /**
     * 当前进程的名称， 类型：String
     */
    public static final String RESNAME_APP_NAME = "APP_NAME";

    /**
     * 当前进程的根目录， 类型：String、File、Path、URI
     */
    public static final String RESNAME_APP_HOME = "APP_HOME";

    /**
     * 当前进程的配置目录，如果不是绝对路径则视为HOME目录下的相对路径 类型：String、File、Path、URI
     */
    public static final String RESNAME_APP_CONF = "APP_CONF";

    /**
     * application.xml 文件中resources节点的内容， 类型： AnyValue
     */
    public static final String RESNAME_APP_GRES = "APP_GRES";

    /**
     * 当前进程节点的nodeid， 类型：int
     */
    public static final String RESNAME_APP_NODEID = "APP_NODEID";

    /**
     * 当前进程节点的IP地址， 类型：InetSocketAddress、InetAddress、String
     */
    public static final String RESNAME_APP_ADDR = "APP_ADDR";

    /**
     * 当前Service所属的SNCP Server的地址 类型: SocketAddress、InetSocketAddress、String <br>
     */
    public static final String RESNAME_SNCP_ADDR = "SNCP_ADDR";

    /**
     * 当前Service所属的SNCP Server所属的组 类型: String<br>
     */
    public static final String RESNAME_SNCP_GROUP = "SNCP_GROUP";

    /**
     * "SERVER_ROOT" 当前Server的ROOT目录类型：String、File、Path
     */
    public static final String RESNAME_SERVER_ROOT = Server.RESNAME_SERVER_ROOT;

    /**
     * 当前Server的线程池
     */
    public static final String RESNAME_SERVER_EXECUTOR = Server.RESNAME_SERVER_EXECUTOR;

    /**
     * 当前Server的ResourceFactory
     */
    public static final String RESNAME_SERVER_RESFACTORY = Server.RESNAME_SERVER_RESFACTORY;

    //本进程节点ID
    final int nodeid;

    //本进程节点ID
    final String name;

    //本地IP地址
    final InetSocketAddress localAddress;

    //CacheSource 资源
    final List<CacheSource> cacheSources = new CopyOnWriteArrayList<>();

    //DataSource 资源
    final List<DataSource> dataSources = new CopyOnWriteArrayList<>();

    //NodeServer 资源, 顺序必须是sncps, others, watchs
    final List<NodeServer> servers = new CopyOnWriteArrayList<>();

    //SNCP传输端的TransportFactory, 注意： 只给SNCP使用
    final TransportFactory sncpTransportFactory;

    //第三方服务发现管理接口
    //@since 2.1.0
    final ClusterAgent clusterAgent;

    //MQ管理接口
    //@since 2.1.0
    final MessageAgent[] messageAgents;

    //全局根ResourceFactory
    final ResourceFactory resourceFactory = ResourceFactory.root();

    //服务配置项
    final AnyValue config;

    //排除的jar路径
    final String excludelibs;

    //临时计数器
    CountDownLatch servicecdl;  //会出现两次赋值

    //是否启动了WATCH协议服务
    boolean watching;

    //--------------------------------------------------------------------------------------------    
    //是否用于main方法运行
    final boolean singletonrun;

    //根WatchFactory
    //private final WatchFactory watchFactory = WatchFactory.root();
    //进程根目录
    private final File home;

    //配置文件目录
    private final URI confPath;

    //日志
    private final Logger logger;

    //监听事件
    private final List<ApplicationListener> listeners = new CopyOnWriteArrayList<>();

    //服务启动时间
    private final long startTime = System.currentTimeMillis();

    //Server启动的计数器，用于确保所有Server都启动完后再进行下一步处理
    private final CountDownLatch serversLatch;

    //根ClassLoader
    private final RedkaleClassLoader classLoader;

    //Server根ClassLoader
    private final RedkaleClassLoader serverClassLoader;

    private Application(final AnyValue config) {
        this(false, config);
    }

    private Application(final boolean singletonrun, final AnyValue config) {
        this.singletonrun = singletonrun;
        this.config = config;
        System.setProperty("redkale.version", Redkale.getDotedVersion());

        final File root = new File(System.getProperty(RESNAME_APP_HOME));
        this.resourceFactory.register(RESNAME_APP_TIME, long.class, this.startTime);
        this.resourceFactory.register(RESNAME_APP_HOME, Path.class, root.toPath());
        this.resourceFactory.register(RESNAME_APP_HOME, File.class, root);
        this.resourceFactory.register(RESNAME_APP_HOME, URI.class, root.toURI());
        try {
            this.resourceFactory.register(RESNAME_APP_HOME, root.getCanonicalPath());
            this.home = root.getCanonicalFile();
            String confsubpath = System.getProperty(RESNAME_APP_CONF, "conf");
            if (confsubpath.contains("://")) {
                this.confPath = new URI(confsubpath);
            } else if (confsubpath.charAt(0) == '/' || confsubpath.indexOf(':') > 0) {
                this.confPath = new File(confsubpath).getCanonicalFile().toURI();
            } else {
                this.confPath = new File(this.home, confsubpath).getCanonicalFile().toURI();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String localaddr = config.getValue("address", "").trim();
        InetAddress addr = localaddr.isEmpty() ? Utility.localInetAddress() : new InetSocketAddress(localaddr, config.getIntValue("port")).getAddress();
        this.localAddress = new InetSocketAddress(addr, config.getIntValue("port"));
        this.resourceFactory.register(RESNAME_APP_ADDR, addr.getHostAddress());
        this.resourceFactory.register(RESNAME_APP_ADDR, InetAddress.class, addr);
        this.resourceFactory.register(RESNAME_APP_ADDR, InetSocketAddress.class, this.localAddress);

        {
            int nid = config.getIntValue("nodeid", 0);
            this.nodeid = nid;
            this.resourceFactory.register(RESNAME_APP_NODEID, nid);
            System.setProperty(RESNAME_APP_NODEID, "" + nid);
        }
        {
            this.name = checkName(config.getValue("name", ""));
            this.resourceFactory.register(RESNAME_APP_NAME, name);
            System.setProperty(RESNAME_APP_NAME, name);
        }
        //以下是初始化日志配置
        final URI logConfURI = "file".equals(confPath.getScheme()) ? new File(new File(confPath), "logging.properties").toURI()
            : URI.create(confPath.toString() + (confPath.toString().endsWith("/") ? "" : "/") + "logging.properties");
        if (!"file".equals(confPath.getScheme()) || (new File(logConfURI).isFile() && new File(logConfURI).canRead())) {
            try {
                final String rootpath = root.getCanonicalPath().replace('\\', '/');
                InputStream fin = logConfURI.toURL().openStream();
                Properties properties = new Properties();
                properties.load(fin);
                fin.close();
                properties.entrySet().stream().forEach(x -> {
                    x.setValue(x.getValue().toString().replace("${APP_HOME}", rootpath));
                });

                if (properties.getProperty("java.util.logging.FileHandler.formatter") == null) {
                    properties.setProperty("java.util.logging.FileHandler.formatter", LogFileHandler.LoggingFormater.class.getName());
                }
                if (properties.getProperty("java.util.logging.ConsoleHandler.formatter") == null) {
                    properties.setProperty("java.util.logging.ConsoleHandler.formatter", LogFileHandler.LoggingFormater.class.getName());
                }
                String fileHandlerPattern = properties.getProperty("java.util.logging.FileHandler.pattern");
                if (fileHandlerPattern != null && fileHandlerPattern.contains("%d")) {
                    final String fileHandlerClass = LogFileHandler.class.getName();
                    Properties prop = new Properties();
                    final String handlers = properties.getProperty("handlers");
                    if (handlers != null && handlers.contains("java.util.logging.FileHandler")) {
                        //singletonrun模式下不输出文件日志
                        prop.setProperty("handlers", handlers.replace("java.util.logging.FileHandler", singletonrun ? "" : fileHandlerClass));
                    }
                    if (!prop.isEmpty()) {
                        String prefix = fileHandlerClass + ".";
                        properties.entrySet().stream().forEach(x -> {
                            if (x.getKey().toString().startsWith("java.util.logging.FileHandler.")) {
                                prop.put(x.getKey().toString().replace("java.util.logging.FileHandler.", prefix), x.getValue());
                            }
                        });
                        prop.entrySet().stream().forEach(x -> {
                            properties.put(x.getKey(), x.getValue());
                        });
                    }
                    properties.put(SncpClient.class.getSimpleName() + ".handlers", LogFileHandler.SncpLogFileHandler.class.getName());
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                final PrintStream ps = new PrintStream(out);
                properties.forEach((x, y) -> ps.println(x + "=" + y));
                LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(out.toByteArray()));
            } catch (Exception e) {
                Logger.getLogger(this.getClass().getSimpleName()).log(Level.WARNING, "init logger configuration error", e);
            }
        }
        this.logger = Logger.getLogger(this.getClass().getSimpleName());
        this.serversLatch = new CountDownLatch(config.getAnyValues("server").length + 1);
        this.classLoader = new RedkaleClassLoader(Thread.currentThread().getContextClassLoader());
        logger.log(Level.INFO, "------------------------- Redkale " + Redkale.getDotedVersion() + " -------------------------");
        //------------------配置 <transport> 节点 ------------------
        ObjectPool<ByteBuffer> transportPool = null;
        ExecutorService transportExec = null;
        AsynchronousChannelGroup transportGroup = null;
        final AnyValue resources = config.getAnyValue("resources");
        TransportStrategy strategy = null;
        String excludelib0 = null;
        ClusterAgent cluster = null;
        MessageAgent[] mqs = null;
        int bufferCapacity = 32 * 1024;
        int bufferPoolSize = Runtime.getRuntime().availableProcessors() * 8;
        int readTimeoutSeconds = TransportFactory.DEFAULT_READTIMEOUTSECONDS;
        int writeTimeoutSeconds = TransportFactory.DEFAULT_WRITETIMEOUTSECONDS;
        AtomicLong createBufferCounter = new AtomicLong();
        AtomicLong cycleBufferCounter = new AtomicLong();
        if (resources != null) {
            AnyValue excludelibConf = resources.getAnyValue("excludelibs");
            if (excludelibConf != null) excludelib0 = excludelibConf.getValue("value");
            AnyValue transportConf = resources.getAnyValue("transport");
            int groupsize = resources.getAnyValues("group").length;
            if (groupsize > 0 && transportConf == null) transportConf = new DefaultAnyValue();
            if (transportConf != null) {
                //--------------transportBufferPool-----------
                bufferCapacity = Math.max(parseLenth(transportConf.getValue("bufferCapacity"), bufferCapacity), 8 * 1024);
                readTimeoutSeconds = transportConf.getIntValue("readTimeoutSeconds", readTimeoutSeconds);
                writeTimeoutSeconds = transportConf.getIntValue("writeTimeoutSeconds", writeTimeoutSeconds);
                final int threads = parseLenth(transportConf.getValue("threads"), groupsize * Runtime.getRuntime().availableProcessors() * 2);
                bufferPoolSize = parseLenth(transportConf.getValue("bufferPoolSize"), threads * 4);
                final int capacity = bufferCapacity;
                transportPool = ObjectPool.createSafePool(createBufferCounter, cycleBufferCounter, bufferPoolSize,
                    (Object... params) -> ByteBuffer.allocateDirect(capacity), null, (e) -> {
                        if (e == null || e.isReadOnly() || e.capacity() != capacity) return false;
                        e.clear();
                        return true;
                    });
                //-----------transportChannelGroup--------------
                try {
                    final String strategyClass = transportConf.getValue("strategy");
                    if (strategyClass != null && !strategyClass.isEmpty()) {
                        strategy = (TransportStrategy) classLoader.loadClass(strategyClass).getDeclaredConstructor().newInstance();
                    }
                    final AtomicInteger counter = new AtomicInteger();
                    transportExec = Executors.newFixedThreadPool(threads, (Runnable r) -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        t.setName("Redkale-Transport-Thread-" + counter.incrementAndGet());
                        return t;
                    });
                    transportGroup = AsynchronousChannelGroup.withCachedThreadPool(transportExec, 1);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                logger.log(Level.INFO, Transport.class.getSimpleName() + " configure bufferCapacity = " + bufferCapacity / 1024 + "K; bufferPoolSize = " + bufferPoolSize + "; threads = " + threads + ";");
            }

            AnyValue clusterConf = resources.getAnyValue("cluster");
            if (clusterConf != null) {
                try {
                    String classval = clusterConf.getValue("value");
                    if (classval == null || classval.isEmpty()) {
                        Iterator<ClusterAgent> it = ServiceLoader.load(ClusterAgent.class, classLoader).iterator();
                        while (it.hasNext()) {
                            ClusterAgent agent = it.next();
                            if (agent.match(clusterConf)) {
                                cluster = agent;
                                cluster.setConfig(clusterConf);
                                break;
                            }
                        }
                        if (cluster == null) {
                            ClusterAgent cacheClusterAgent = new CacheClusterAgent();
                            if (cacheClusterAgent.match(clusterConf)) {
                                cluster = cacheClusterAgent;
                                cluster.setConfig(clusterConf);
                            }
                        }
                        if (cluster == null) logger.log(Level.SEVERE, "load application cluster resource, but not found name='value' value error: " + clusterConf);
                    } else {
                        Class type = classLoader.loadClass(clusterConf.getValue("value"));
                        if (!ClusterAgent.class.isAssignableFrom(type)) {
                            logger.log(Level.SEVERE, "load application cluster resource, but not found " + ClusterAgent.class.getSimpleName() + " implements class error: " + clusterConf);
                        } else {
                            cluster = (ClusterAgent) type.getDeclaredConstructor().newInstance();
                            cluster.setConfig(clusterConf);
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "load application cluster resource error: " + clusterConf, e);
                }
            }

            AnyValue[] mqConfs = resources.getAnyValues("mq");
            if (mqConfs != null && mqConfs.length > 0) {
                mqs = new MessageAgent[mqConfs.length];
                Set<String> mqnames = new HashSet<>();
                for (int i = 0; i < mqConfs.length; i++) {
                    AnyValue mqConf = mqConfs[0];
                    String mqname = mqConf.getValue("name", "");
                    if (mqnames.contains(mqname)) throw new RuntimeException("mq.name(" + mqname + ") is repeat");
                    mqnames.add(mqname);
                    String namex = mqConf.getValue("names");
                    if (namex != null && !namex.isEmpty()) {
                        for (String n : namex.split(";")) {
                            if (n.trim().isEmpty()) continue;
                            if (mqnames.contains(n.trim())) throw new RuntimeException("mq.name(" + n.trim() + ") is repeat");
                            mqnames.add(n.trim());
                        }
                    }
                    try {
                        String classval = mqConf.getValue("value");
                        if (classval == null || classval.isEmpty()) {
                            Iterator<MessageAgent> it = ServiceLoader.load(MessageAgent.class, classLoader).iterator();
                            while (it.hasNext()) {
                                MessageAgent messageAgent = it.next();
                                if (messageAgent.match(mqConf)) {
                                    mqs[i] = messageAgent;
                                    mqs[i].setConfig(mqConf);
                                    break;
                                }
                            }
                            if (mqs[i] == null) logger.log(Level.SEVERE, "load application mq resource, but not found name='value' value error: " + mqConf);
                        } else {
                            Class type = classLoader.loadClass(classval);
                            if (!MessageAgent.class.isAssignableFrom(type)) {
                                logger.log(Level.SEVERE, "load application mq resource, but not found " + MessageAgent.class.getSimpleName() + " implements class error: " + mqConf);
                            } else {
                                mqs[i] = (MessageAgent) type.getDeclaredConstructor().newInstance();
                                mqs[i].setConfig(mqConf);
                            }
                        }
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "load application mq resource error: " + mqs[i], e);
                    }
                }
            }
        }
        if (transportGroup == null) {
            final AtomicInteger counter = new AtomicInteger();
            transportExec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 8, (Runnable r) -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("Redkale-Transport-Thread-" + counter.incrementAndGet());
                return t;
            });
            try {
                transportGroup = AsynchronousChannelGroup.withCachedThreadPool(transportExec, 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (transportPool == null) {
            final int capacity = bufferCapacity;
            transportPool = ObjectPool.createSafePool(createBufferCounter, cycleBufferCounter, bufferPoolSize,
                (Object... params) -> ByteBuffer.allocateDirect(capacity), null, (e) -> {
                    if (e == null || e.isReadOnly() || e.capacity() != capacity) return false;
                    e.clear();
                    return true;
                });
        }
        this.excludelibs = excludelib0;
        this.sncpTransportFactory = TransportFactory.create(transportExec, transportPool, transportGroup, (SSLContext) null, readTimeoutSeconds, writeTimeoutSeconds, strategy);
        DefaultAnyValue tarnsportConf = DefaultAnyValue.create(TransportFactory.NAME_POOLMAXCONNS, System.getProperty("net.transport.pool.maxconns", "100"))
            .addValue(TransportFactory.NAME_PINGINTERVAL, System.getProperty("net.transport.ping.interval", "30"))
            .addValue(TransportFactory.NAME_CHECKINTERVAL, System.getProperty("net.transport.check.interval", "30"));
        this.sncpTransportFactory.init(tarnsportConf, Sncp.PING_BUFFER, Sncp.PONG_BUFFER.remaining());
        this.clusterAgent = cluster;
        this.messageAgents = mqs;
        Thread.currentThread().setContextClassLoader(this.classLoader);
        this.serverClassLoader = new RedkaleClassLoader(this.classLoader);
    }

    private String checkName(String name) {  //不能含特殊字符
        if (name.isEmpty()) return name;
        if (name.charAt(0) >= '0' && name.charAt(0) <= '9') throw new RuntimeException("name only 0-9 a-z A-Z _ cannot begin 0-9");
        for (char ch : name.toCharArray()) {
            if (!((ch >= '0' && ch <= '9') || ch == '_' || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z'))) { //不能含特殊字符
                throw new RuntimeException("name only 0-9 a-z A-Z _ cannot begin 0-9");
            }
        }
        return name;
    }

    public ResourceFactory getResourceFactory() {
        return resourceFactory;
    }

    public TransportFactory getSncpTransportFactory() {
        return sncpTransportFactory;
    }

    public ClusterAgent getClusterAgent() {
        return clusterAgent;
    }

    public MessageAgent getMessageAgent(String name) {
        if (messageAgents == null) return null;
        for (MessageAgent agent : messageAgents) {
            if (agent.getName().equals(name)) return agent;
        }
        return null;
    }

    public MessageAgent[] getMessageAgents() {
        return messageAgents;
    }

    public RedkaleClassLoader getClassLoader() {
        return classLoader;
    }

    public RedkaleClassLoader getServerClassLoader() {
        return serverClassLoader;
    }

    public List<NodeServer> getNodeServers() {
        return new ArrayList<>(servers);
    }

    public List<DataSource> getDataSources() {
        return new ArrayList<>(dataSources);
    }

    public List<CacheSource> getCacheSources() {
        return new ArrayList<>(cacheSources);
    }

    public int getNodeid() {
        return nodeid;
    }

    public String getName() {
        return name;
    }

    public File getHome() {
        return home;
    }

    public URI getConfPath() {
        return confPath;
    }

    public long getStartTime() {
        return startTime;
    }

    public AnyValue getAppConfig() {
        return config;
    }

    public void init() throws Exception {
        System.setProperty("net.transport.poolmaxconns", "100");
        System.setProperty("net.transport.pinginterval", "30");
        System.setProperty("net.transport.checkinterval", "30");
        System.setProperty("convert.tiny", "true");
        System.setProperty("convert.pool.size", "128");
        System.setProperty("convert.writer.buffer.defsize", "4096");

        final String confpath = this.confPath.toString();
        final String homepath = this.home.getCanonicalPath();
        if ("file".equals(this.confPath.getScheme())) {
            File persist = new File(new File(confPath), "persistence.xml");
            if (persist.isFile()) System.setProperty(DataSources.DATASOURCE_CONFPATH, persist.getCanonicalPath());
        } else {
            System.setProperty(DataSources.DATASOURCE_CONFPATH, confpath + (confpath.endsWith("/") ? "" : "/") + "persistence.xml");
        }
        String pidstr = "";
        try { //JDK 9+
            Class phclass = Class.forName("java.lang.ProcessHandle");
            Object phobj = phclass.getMethod("current").invoke(null);
            Object pid = phclass.getMethod("pid").invoke(phobj);
            pidstr = "APP_PID  = " + pid + "\r\n";
        } catch (Throwable t) {
        }
        logger.log(Level.INFO, pidstr + "APP_JAVA = " + System.getProperty("java.version") + "\r\n" + RESNAME_APP_NODEID + " = " + this.nodeid + "\r\n" + RESNAME_APP_ADDR + " = " + this.localAddress.getHostString() + ":" + this.localAddress.getPort() + "\r\n" + RESNAME_APP_HOME + " = " + homepath + "\r\n" + RESNAME_APP_CONF + " = " + confpath);
        String lib = config.getValue("lib", "${APP_HOME}/libs/*").trim().replace("${APP_HOME}", homepath);
        lib = lib.isEmpty() ? confpath : (lib + ";" + confpath);
        Server.loadLib(classLoader, logger, lib);

        //------------------------------------------------------------------------
        final AnyValue resources = config.getAnyValue("resources");
        if (resources != null) {
            resourceFactory.register(RESNAME_APP_GRES, AnyValue.class, resources);
            final AnyValue properties = resources.getAnyValue("properties");
            if (properties != null) {
                String dfloads = properties.getValue("load");
                if (dfloads != null) {
                    for (String dfload : dfloads.split(";")) {
                        if (dfload.trim().isEmpty()) continue;
                        final URI df = (dfload.indexOf('/') < 0) ? URI.create(confpath + (confpath.endsWith("/") ? "" : "/") + dfload) : new File(dfload).toURI();
                        if (!"file".equals(df.getScheme()) || new File(df).isFile()) {
                            Properties ps = new Properties();
                            try {
                                InputStream in = df.toURL().openStream();
                                ps.load(in);
                                in.close();
                                ps.forEach((x, y) -> resourceFactory.register("property." + x, y.toString().replace("${APP_HOME}", homepath)));
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "load properties(" + dfload + ") error", e);
                            }
                        }
                    }
                }
                for (AnyValue prop : properties.getAnyValues("property")) {
                    String name = prop.getValue("name");
                    String value = prop.getValue("value");
                    if (name == null || value == null) continue;
                    value = value.replace("${APP_HOME}", homepath);
                    if (name.startsWith("system.property.")) {
                        System.setProperty(name.substring("system.property.".length()), value);
                    } else if (name.startsWith("mimetype.property.")) {
                        MimeType.add(name.substring("mimetype.property.".length()), value);
                    } else if (name.startsWith("property.")) {
                        resourceFactory.register(name, value);
                    } else {
                        resourceFactory.register("property." + name, value);
                    }
                }
            }
        }
        this.resourceFactory.register(BsonFactory.root());
        this.resourceFactory.register(JsonFactory.root());
        this.resourceFactory.register(BsonFactory.root().getConvert());
        this.resourceFactory.register(JsonFactory.root().getConvert());
        this.resourceFactory.register("bsonconvert", Convert.class, BsonFactory.root().getConvert());
        this.resourceFactory.register("jsonconvert", Convert.class, JsonFactory.root().getConvert());
        //只有WatchService才能加载Application、WatchFactory
        final Application application = this;
        this.resourceFactory.register(new ResourceFactory.ResourceLoader() {

            @Override
            public void load(ResourceFactory rf, final Object src, String resourceName, Field field, final Object attachment) {
                try {
                    Resource res = field.getAnnotation(Resource.class);
                    if (res == null) return;
                    if (src instanceof Service && Sncp.isRemote((Service) src)) return; //远程模式不得注入 
                    Class type = field.getType();
                    if (type == Application.class) {
                        field.set(src, application);
                    } else if (type == ResourceFactory.class) {
                        boolean serv = RESNAME_SERVER_RESFACTORY.equals(res.name()) || res.name().equalsIgnoreCase("server");
                        field.set(src, serv ? rf : (res.name().isEmpty() ? application.resourceFactory : null));
                    } else if (type == TransportFactory.class) {
                        field.set(src, application.sncpTransportFactory);
                    } else if (type == NodeSncpServer.class) {
                        NodeServer server = null;
                        for (NodeServer ns : application.getNodeServers()) {
                            if (ns.getClass() == NodeSncpServer.class) continue;
                            if (res.name().equals(ns.server.getName())) {
                                server = ns;
                                break;
                            }
                        }
                        field.set(src, server);
                    } else if (type == NodeHttpServer.class) {
                        NodeServer server = null;
                        for (NodeServer ns : application.getNodeServers()) {
                            if (ns.getClass() == NodeHttpServer.class) continue;
                            if (res.name().equals(ns.server.getName())) {
                                server = ns;
                                break;
                            }
                        }
                        field.set(src, server);
                    } else if (type == NodeWatchServer.class) {
                        NodeServer server = null;
                        for (NodeServer ns : application.getNodeServers()) {
                            if (ns.getClass() == NodeWatchServer.class) continue;
                            if (res.name().equals(ns.server.getName())) {
                                server = ns;
                                break;
                            }
                        }
                        field.set(src, server);
                    }
//                    if (type == WatchFactory.class) {
//                        field.set(src, application.watchFactory);
//                    }
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Resource inject error", e);
                }
            }

            @Override
            public boolean autoNone() {
                return false;
            }

        }, Application.class, ResourceFactory.class, TransportFactory.class, NodeSncpServer.class, NodeHttpServer.class, NodeWatchServer.class);
        //--------------------------------------------------------------------------
        if (this.clusterAgent != null) {
            if (logger.isLoggable(Level.FINER)) logger.log(Level.FINER, "ClusterAgent initing");
            long s = System.currentTimeMillis();
            if (this.clusterAgent instanceof CacheClusterAgent) {
                String sourceName = ((CacheClusterAgent) clusterAgent).getSourceName(); //必须在inject前调用，需要赋值Resourcable.name
                loadCacheSource(sourceName);
            }
            clusterAgent.setTransportFactory(this.sncpTransportFactory);
            this.resourceFactory.inject(clusterAgent);
            clusterAgent.init(clusterAgent.getConfig());
            this.resourceFactory.register(ClusterAgent.class, clusterAgent);
            logger.info("ClusterAgent init in " + (System.currentTimeMillis() - s) + " ms");
        }
        if (this.messageAgents != null) {
            if (logger.isLoggable(Level.FINER)) logger.log(Level.FINER, "MessageAgent initing");
            long s = System.currentTimeMillis();
            for (MessageAgent agent : this.messageAgents) {
                this.resourceFactory.inject(agent);
                agent.init(agent.getConfig());
                this.resourceFactory.register(agent.getName(), MessageAgent.class, agent);
                this.resourceFactory.register(agent.getName(), HttpMessageClient.class, agent.getHttpMessageClient());
                //this.resourceFactory.register(agent.getName(), SncpMessageClient.class, agent.getSncpMessageClient()); //不需要给开发者使用
            }
            logger.info("MessageAgent init in " + (System.currentTimeMillis() - s) + " ms");

        }
        //------------------------------------- 注册 HttpMessageClient --------------------------------------------------------        
        resourceFactory.register((ResourceFactory rf, final Object src, String resourceName, Field field, final Object attachment) -> {
            try {
                if (field.getAnnotation(Resource.class) == null) return;
                if (clusterAgent == null) return;
                HttpMessageClient messageClient = new HttpMessageClusterClient(clusterAgent);
                field.set(src, messageClient);
                rf.inject(messageClient, null); // 给其可能包含@Resource的字段赋值;
                rf.register(resourceName, HttpMessageClient.class, messageClient);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "[" + Thread.currentThread().getName() + "] HttpMessageClient inject error", e);
            }
        }, HttpMessageClient.class);
        initResources();
    }

    private void loadCacheSource(final String sourceName) {
        final AnyValue resources = config.getAnyValue("resources");
        for (AnyValue sourceConf : resources.getAnyValues("source")) {
            if (!sourceName.equals(sourceConf.getValue("name"))) continue;
            String classval = sourceConf.getValue("value");
            try {
                Class sourceType = CacheMemorySource.class;
                if (classval == null || classval.isEmpty()) {
                    Iterator<CacheSource> it = ServiceLoader.load(CacheSource.class, serverClassLoader).iterator();
                    while (it.hasNext()) {
                        CacheSource s = it.next();
                        if (s.match(sourceConf)) {
                            sourceType = s.getClass();
                            break;
                        }
                    }
                } else {
                    sourceType = serverClassLoader.loadClass(classval);
                }
                CacheSource source = Modifier.isFinal(sourceType.getModifiers()) ? (CacheSource) sourceType.getConstructor().newInstance() : (CacheSource) Sncp.createLocalService(serverClassLoader, sourceName, sourceType, null, resourceFactory, sncpTransportFactory, null, null, sourceConf);
                cacheSources.add((CacheSource) source);
                resourceFactory.register(sourceName, CacheSource.class, source);
                resourceFactory.inject(source);
                if (source instanceof Service) ((Service) source).init(sourceConf);
                logger.info("[" + Thread.currentThread().getName() + "] Load Source resourceName = " + sourceName + ", source = " + source);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "load application source resource error: " + sourceConf, e);
            }
            return;
        }
    }

    private void initResources() throws Exception {
        //-------------------------------------------------------------------------
        final AnyValue resources = config.getAnyValue("resources");
        if (resources != null) {
            //------------------------------------------------------------------------
            for (AnyValue conf : resources.getAnyValues("group")) {
                final String group = conf.getValue("name", "");
                final String protocol = conf.getValue("protocol", Transport.DEFAULT_PROTOCOL).toUpperCase();
                if (!"TCP".equalsIgnoreCase(protocol) && !"UDP".equalsIgnoreCase(protocol)) {
                    throw new RuntimeException("Not supported Transport Protocol " + conf.getValue("protocol"));
                }
                TransportGroupInfo ginfo = new TransportGroupInfo(group, protocol, new LinkedHashSet<>());
                for (AnyValue node : conf.getAnyValues("node")) {
                    final InetSocketAddress addr = new InetSocketAddress(node.getValue("addr"), node.getIntValue("port"));
                    ginfo.putAddress(addr);
                }
                sncpTransportFactory.addGroupInfo(ginfo);
            }
            for (AnyValue conf : resources.getAnyValues("listener")) {
                final String listenClass = conf.getValue("value", "");
                if (listenClass.isEmpty()) continue;
                Class clazz = classLoader.loadClass(listenClass);
                if (!ApplicationListener.class.isAssignableFrom(clazz)) continue;
                @SuppressWarnings("unchecked")
                ApplicationListener listener = (ApplicationListener) clazz.getDeclaredConstructor().newInstance();
                resourceFactory.inject(listener);
                listener.init(config);
                this.listeners.add(listener);
            }
        }
        //------------------------------------------------------------------------
    }

    private void startSelfServer() throws Exception {
        final Application application = this;
        new Thread() {
            {
                setName("Redkale-Application-SelfServer-Thread");
            }

            @Override
            public void run() {
                try {
                    final DatagramChannel channel = DatagramChannel.open();
                    channel.configureBlocking(true);
                    channel.socket().setSoTimeout(3000);
                    channel.bind(new InetSocketAddress("127.0.0.1", config.getIntValue("port")));
                    boolean loop = true;
                    ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
                    while (loop) {
                        buffer.clear();
                        SocketAddress address = channel.receive(buffer);
                        buffer.flip();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        final String cmd = new String(bytes);
                        if ("SHUTDOWN".equalsIgnoreCase(cmd)) {
                            try {
                                long s = System.currentTimeMillis();
                                logger.info(application.getClass().getSimpleName() + " shutdowning");
                                application.shutdown();
                                buffer.clear();
                                buffer.put("SHUTDOWN OK".getBytes());
                                buffer.flip();
                                channel.send(buffer, address);
                                long e = System.currentTimeMillis() - s;
                                logger.info(application.getClass().getSimpleName() + " shutdown in " + e + " ms");
                                application.serversLatch.countDown();
                                System.exit(0);
                            } catch (Exception ex) {
                                logger.log(Level.INFO, "SHUTDOWN FAIL", ex);
                                buffer.clear();
                                buffer.put("SHUTDOWN FAIL".getBytes());
                                buffer.flip();
                                channel.send(buffer, address);
                            }
                        } else if ("APIDOC".equalsIgnoreCase(cmd)) {
                            try {
                                new ApiDocsService(application).run();
                                buffer.clear();
                                buffer.put("APIDOC OK".getBytes());
                                buffer.flip();
                                channel.send(buffer, address);
                            } catch (Exception ex) {
                                buffer.clear();
                                buffer.put("APIDOC FAIL".getBytes());
                                buffer.flip();
                                channel.send(buffer, address);
                            }
                        } else {
                            long s = System.currentTimeMillis();
                            logger.info(application.getClass().getSimpleName() + " command " + cmd);
                            application.command(cmd);
                            buffer.clear();
                            buffer.put("COMMAND OK".getBytes());
                            buffer.flip();
                            channel.send(buffer, address);
                            long e = System.currentTimeMillis() - s;
                            logger.info(application.getClass().getSimpleName() + " command in " + e + " ms");
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.INFO, "Control fail", e);
                    System.exit(1);
                }
            }
        }.start();
    }

    private static void sendCommand(Logger logger, int port, String command) throws Exception {
        final DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(true);
        channel.connect(new InetSocketAddress("127.0.0.1", port));
        ByteBuffer buffer = ByteBuffer.allocate(128);
        buffer.put(command.getBytes());
        buffer.flip();
        channel.write(buffer);
        buffer.clear();
        channel.configureBlocking(true);
        try {
            channel.read(buffer);
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            channel.close();
            if (logger != null) logger.info("Send: " + command + ", Reply: " + new String(bytes));
            Thread.sleep(1000);
        } catch (Exception e) {
            if (e instanceof PortUnreachableException) {
                if ("APIDOC".equalsIgnoreCase(command)) {
                    final Application application = Application.create(true);
                    application.init();
                    application.start();
                    new ApiDocsService(application).run();
                    if (logger != null) logger.info("APIDOC OK");
                    return;
                }
            }
            throw e;
        }
    }

    public void start() throws Exception {
        if (!singletonrun && this.clusterAgent != null) {
            this.clusterAgent.register(this);
        }
        final AnyValue[] entrys = config.getAnyValues("server");
        CountDownLatch timecd = new CountDownLatch(entrys.length);
        final List<AnyValue> sncps = new ArrayList<>();
        final List<AnyValue> others = new ArrayList<>();
        final List<AnyValue> watchs = new ArrayList<>();
        for (final AnyValue entry : entrys) {
            if (entry.getValue("protocol", "").toUpperCase().startsWith("SNCP")) {
                sncps.add(entry);
            } else if (entry.getValue("protocol", "").toUpperCase().startsWith("WATCH")) {
                watchs.add(entry);
            } else {
                others.add(entry);
            }
        }
        if (watchs.size() > 1) throw new RuntimeException("Found one more WATCH Server");
        this.watching = !watchs.isEmpty();

        runServers(timecd, sncps);  //必须确保SNCP服务都启动后再启动其他服务
        runServers(timecd, others);
        runServers(timecd, watchs); //必须在所有服务都启动后再启动WATCH服务
        timecd.await();
        if (this.clusterAgent != null) this.clusterAgent.start();
        if (this.messageAgents != null) {
            if (logger.isLoggable(Level.FINER)) logger.log(Level.FINER, "MessageAgent starting");
            long s = System.currentTimeMillis();
            final StringBuffer sb = new StringBuffer();
            Set<String> names = new HashSet<>();
            for (MessageAgent agent : this.messageAgents) {
                names.add(agent.getName());
                Map<String, Long> map = agent.start().join();
                AtomicInteger maxlen = new AtomicInteger();
                map.keySet().forEach(str -> {
                    if (str.length() > maxlen.get()) maxlen.set(str.length());
                });
                new TreeMap<>(map).forEach((topic, ms) -> sb.append("MessageConsumer(topic=").append(alignString(topic, maxlen.get())).append(") init and start in ").append(ms).append(" ms\r\n")
                );
            }
            if (sb.length() > 0) logger.info(sb.toString().trim());
            logger.info("MessageAgent(names=" + JsonConvert.root().convertTo(names) + ") start in " + (System.currentTimeMillis() - s) + " ms");
        }
        //if (!singletonrun) signalHandle();
        //if (!singletonrun) clearPersistData();
        logger.info(this.getClass().getSimpleName() + " started in " + (System.currentTimeMillis() - startTime) + " ms\r\n");
        for (ApplicationListener listener : this.listeners) {
            listener.postStart(this);
        }
        if (!singletonrun) this.serversLatch.await();
    }

    private static String alignString(String value, int maxlen) {
        StringBuilder sb = new StringBuilder(maxlen);
        sb.append(value);
        for (int i = 0; i < maxlen - value.length(); i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

//    private void clearPersistData() {
//        File cachedir = new File(home, "cache");
//        if (!cachedir.isDirectory()) return;
//        File[] lfs = cachedir.listFiles();
//        if (lfs != null) {
//            for (File file : lfs) {
//                if (file.getName().startsWith("persist-")) file.delete();
//            }
//        }
//    }
//    private void signalHandle() {
//        //http://www.comptechdoc.org/os/linux/programming/linux_pgsignals.html
//        String[] sigs = new String[]{"HUP", "TERM", "INT", "QUIT", "KILL", "TSTP", "USR1", "USR2", "STOP"};
//        List<sun.misc.Signal> list = new ArrayList<>();
//        for (String sig : sigs) {
//            try {
//                list.add(new sun.misc.Signal(sig));
//            } catch (Exception e) {
//            }
//        }
//        sun.misc.SignalHandler handler = new sun.misc.SignalHandler() {
//
//            private volatile boolean runed;
//
//            @Override
//            public void handle(Signal sig) {
//                if (runed) return;
//                runed = true;
//                logger.info(Application.this.getClass().getSimpleName() + " stoped\r\n");
//                System.exit(0);
//            }
//        };
//        for (Signal sig : list) {
//            try {
//                Signal.handle(sig, handler);
//            } catch (Exception e) {
//            }
//        }
//    }
    @SuppressWarnings("unchecked")
    private void runServers(CountDownLatch timecd, final List<AnyValue> serconfs) throws Exception {
        this.servicecdl = new CountDownLatch(serconfs.size());
        CountDownLatch sercdl = new CountDownLatch(serconfs.size());
        final AtomicBoolean inited = new AtomicBoolean(false);
        final Map<String, Class<? extends NodeServer>> nodeClasses = new HashMap<>();
        for (final AnyValue serconf : serconfs) {
            Thread thread = new Thread() {
                {
                    String host = serconf.getValue("host", "0.0.0.0").replace("0.0.0.0", "*");
                    setName("Redkale-" + serconf.getValue("protocol", "Server").toUpperCase() + "-" + host + ":" + serconf.getIntValue("port") + "-Thread");
                    this.setDaemon(true);
                }

                @Override
                public void run() {
                    try {
                        //Thread ctd = Thread.currentThread();
                        //ctd.setContextClassLoader(new URLClassLoader(new URL[0], ctd.getContextClassLoader()));
                        final String protocol = serconf.getValue("protocol", "").replaceFirst("\\..+", "").toUpperCase();
                        NodeServer server = null;
                        if ("SNCP".equals(protocol)) {
                            server = NodeSncpServer.createNodeServer(Application.this, serconf);
                        } else if ("WATCH".equalsIgnoreCase(protocol)) {
                            DefaultAnyValue serconf2 = (DefaultAnyValue) serconf;
                            DefaultAnyValue rest = (DefaultAnyValue) serconf2.getAnyValue("rest");
                            if (rest == null) {
                                rest = new DefaultAnyValue();
                                serconf2.addValue("rest", rest);
                            }
                            rest.setValue("base", WatchServlet.class.getName());
                            server = new NodeWatchServer(Application.this, serconf);
                        } else if ("HTTP".equalsIgnoreCase(protocol)) {
                            server = new NodeHttpServer(Application.this, serconf);
                        } else {
                            if (!inited.get()) {
                                synchronized (nodeClasses) {
                                    if (!inited.getAndSet(true)) { //加载自定义的协议，如：SOCKS
                                        ClassFilter profilter = new ClassFilter(classLoader, NodeProtocol.class, NodeServer.class, (Class[]) null);
                                        ClassFilter.Loader.load(home, ((excludelibs != null ? (excludelibs + ";") : "") + serconf.getValue("excludelibs", "")).split(";"), profilter);
                                        final Set<FilterEntry<NodeServer>> entrys = profilter.getFilterEntrys();
                                        for (FilterEntry<NodeServer> entry : entrys) {
                                            final Class<? extends NodeServer> type = entry.getType();
                                            NodeProtocol pros = type.getAnnotation(NodeProtocol.class);
                                            String p = pros.value().toUpperCase();
                                            if ("SNCP".equals(p) || "HTTP".equals(p)) continue;
                                            final Class<? extends NodeServer> old = nodeClasses.get(p);
                                            if (old != null && old != type) {
                                                throw new RuntimeException("Protocol(" + p + ") had NodeServer-Class(" + old.getName() + ") but repeat NodeServer-Class(" + type.getName() + ")");
                                            }
                                            nodeClasses.put(p, type);
                                        }
                                    }
                                }
                            }
                            Class<? extends NodeServer> nodeClass = nodeClasses.get(protocol);
                            if (nodeClass != null) server = NodeServer.create(nodeClass, Application.this, serconf);
                        }
                        if (server == null) {
                            logger.log(Level.SEVERE, "Not found Server Class for protocol({0})", serconf.getValue("protocol"));
                            System.exit(0);
                        }
                        servers.add(server);
                        server.init(serconf);
                        if (!singletonrun) server.start();
                        timecd.countDown();
                        sercdl.countDown();
                    } catch (Exception ex) {
                        logger.log(Level.WARNING, serconf + " runServers error", ex);
                        Application.this.serversLatch.countDown();
                    }
                }
            };
            thread.start();
        }
        sercdl.await();
    }

    public static <T extends Service> T singleton(Class<T> serviceClass, Class<? extends Service>... extServiceClasses) throws Exception {
        return singleton("", serviceClass, extServiceClasses);
    }

    public static <T extends Service> T singleton(String name, Class<T> serviceClass, Class<? extends Service>... extServiceClasses) throws Exception {
        if (serviceClass == null) throw new IllegalArgumentException("serviceClass is null");
        final Application application = Application.create(true);
        System.setProperty("red" + "kale.singleton.serviceclass", serviceClass.getName());
        if (extServiceClasses != null && extServiceClasses.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (Class clazz : extServiceClasses) {
                if (sb.length() > 0) sb.append(',');
                sb.append(clazz.getName());
            }
            System.setProperty("red" + "kale.singleton.extserviceclasses", sb.toString());
        }
        application.init();
        application.start();
        for (NodeServer server : application.servers) {
            T service = server.resourceFactory.find(name, serviceClass);
            if (service != null) return service;
        }
        if (Modifier.isAbstract(serviceClass.getModifiers())) throw new IllegalArgumentException("abstract class not allowed");
        if (serviceClass.isInterface()) throw new IllegalArgumentException("interface class not allowed");
        throw new IllegalArgumentException(serviceClass.getName() + " maybe have zero not-final public method");
    }

    public static Application create(final boolean singleton) throws IOException {
        return new Application(singleton, loadAppXml());
    }

    public void reloadConfig() throws IOException {
        AnyValue newconfig = loadAppXml();
        final String confpath = this.confPath.toString();
        final String homepath = this.home.getCanonicalPath();
        final AnyValue resources = newconfig.getAnyValue("resources");
        if (resources != null) {
            resourceFactory.register(RESNAME_APP_GRES, AnyValue.class, resources);
            final AnyValue properties = resources.getAnyValue("properties");
            if (properties != null) {
                String dfloads = properties.getValue("load");
                if (dfloads != null) {
                    for (String dfload : dfloads.split(";")) {
                        if (dfload.trim().isEmpty()) continue;
                        final URI df = (dfload.indexOf('/') < 0) ? URI.create(confpath + (confpath.endsWith("/") ? "" : "/") + dfload) : new File(dfload).toURI();
                        if (!"file".equals(df.getScheme()) || new File(df).isFile()) {
                            Properties ps = new Properties();
                            try {
                                InputStream in = df.toURL().openStream();
                                ps.load(in);
                                in.close();
                                ps.forEach((x, y) -> resourceFactory.register("property." + x, y.toString().replace("${APP_HOME}", homepath)));
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "load properties(" + dfload + ") error", e);
                            }
                        }
                    }
                }
                for (AnyValue prop : properties.getAnyValues("property")) {
                    String name = prop.getValue("name");
                    String value = prop.getValue("value");
                    if (name == null || value == null) continue;
                    value = value.replace("${APP_HOME}", homepath);
                    if (name.startsWith("system.property.")) {
                        System.setProperty(name.substring("system.property.".length()), value);
                    } else if (name.startsWith("mimetype.property.")) {
                        MimeType.add(name.substring("mimetype.property.".length()), value);
                    } else if (name.startsWith("property.")) {
                        resourceFactory.register(name, value);
                    } else {
                        resourceFactory.register("property." + name, value);
                    }
                }
            }
        }
    }

    private static AnyValue loadAppXml() throws IOException {
        final String home = new File(System.getProperty(RESNAME_APP_HOME, "")).getCanonicalPath().replace('\\', '/');
        System.setProperty(RESNAME_APP_HOME, home);
        String confsubpath = System.getProperty(RESNAME_APP_CONF, "conf");
        URI appconf;
        if (confsubpath.contains("://")) {
            appconf = URI.create(confsubpath + (confsubpath.endsWith("/") ? "" : "/") + "application.xml");
        } else if (confsubpath.charAt(0) == '/' || confsubpath.indexOf(':') > 0) {
            appconf = new File(confsubpath, "application.xml").toURI();
        } else {
            appconf = new File(new File(home, confsubpath), "application.xml").toURI();
        }
        return load(appconf.toURL().openStream());
    }

    public static void main(String[] args) throws Exception {
        Utility.midnight(); //先初始化一下Utility
        Thread.currentThread().setName("Redkale-Application-Main-Thread");
        //运行主程序
        if (System.getProperty("CMD") != null) {
            AnyValue config = loadAppXml();
            Application.sendCommand(null, config.getIntValue("port"), System.getProperty("CMD"));
            return;
        }
        final Application application = Application.create(false);
        application.init();
        application.startSelfServer();
        try {
            for (ApplicationListener listener : application.listeners) {
                listener.preStart(application);
            }
            application.start();
        } catch (Exception e) {
            application.logger.log(Level.SEVERE, "Application start error", e);
            System.exit(0);
        }
        System.exit(0);
    }

    NodeSncpServer findNodeSncpServer(final InetSocketAddress sncpAddr) {
        for (NodeServer node : servers) {
            if (node.isSNCP() && sncpAddr.equals(node.getSncpAddress())) {
                return (NodeSncpServer) node;
            }
        }
        return null;
    }

    public void command(String cmd) {
        List<NodeServer> localServers = new ArrayList<>(servers); //顺序sncps, others, watchs        
        localServers.stream().forEach((server) -> {
            try {
                server.command(cmd);
            } catch (Exception t) {
                logger.log(Level.WARNING, " command server(" + server.getSocketAddress() + ") error", t);
            }
        });
    }

    public void shutdown() throws Exception {
        for (ApplicationListener listener : this.listeners) {
            try {
                listener.preShutdown(this);
            } catch (Exception e) {
                logger.log(Level.WARNING, listener.getClass() + " preShutdown erroneous", e);
            }
        }
        List<NodeServer> localServers = new ArrayList<>(servers); //顺序sncps, others, watchs
        Collections.reverse(localServers); //倒序， 必须让watchs先关闭，watch包含服务发现和注销逻辑
        if (this.messageAgents != null) {
            Set<String> names = new HashSet<>();
            if (logger.isLoggable(Level.FINER)) logger.log(Level.FINER, "MessageAgent stopping");
            long s = System.currentTimeMillis();
            for (MessageAgent agent : this.messageAgents) {
                names.add(agent.getName());
                agent.stop().join();
            }
            logger.info("MessageAgent(names=" + JsonConvert.root().convertTo(names) + ") stop in " + (System.currentTimeMillis() - s) + " ms");
        }
        if (clusterAgent != null) {
            if (logger.isLoggable(Level.FINER)) logger.log(Level.FINER, "ClusterAgent destroying");
            long s = System.currentTimeMillis();
            clusterAgent.deregister(this);
            clusterAgent.destroy(clusterAgent.getConfig());
            logger.info("ClusterAgent destroy in " + (System.currentTimeMillis() - s) + " ms");
        }
        localServers.stream().forEach((server) -> {
            try {
                server.shutdown();
            } catch (Exception t) {
                logger.log(Level.WARNING, " shutdown server(" + server.getSocketAddress() + ") error", t);
            } finally {
                serversLatch.countDown();
            }
        });
        if (this.messageAgents != null) {
            Set<String> names = new HashSet<>();
            if (logger.isLoggable(Level.FINER)) logger.log(Level.FINER, "MessageAgent destroying");
            long s = System.currentTimeMillis();
            for (MessageAgent agent : this.messageAgents) {
                names.add(agent.getName());
                agent.destroy(agent.getConfig());
            }
            logger.info("MessageAgent(names=" + JsonConvert.root().convertTo(names) + ") destroy in " + (System.currentTimeMillis() - s) + " ms");
        }
        for (DataSource source : dataSources) {
            if (source == null) continue;
            try {
                source.getClass().getMethod("close").invoke(source);
            } catch (Exception e) {
                logger.log(Level.FINER, source.getClass() + " close DataSource erroneous", e);
            }
        }
        for (CacheSource source : cacheSources) {
            if (source == null) continue;
            try {
                source.getClass().getMethod("close").invoke(source);
            } catch (Exception e) {
                logger.log(Level.FINER, source.getClass() + " close CacheSource erroneous", e);
            }
        }
        this.sncpTransportFactory.shutdownNow();
    }

    private static int parseLenth(String value, int defValue) {
        if (value == null) return defValue;
        value = value.toUpperCase().replace("B", "");
        if (value.endsWith("G")) return Integer.decode(value.replace("G", "")) * 1024 * 1024 * 1024;
        if (value.endsWith("M")) return Integer.decode(value.replace("M", "")) * 1024 * 1024;
        if (value.endsWith("K")) return Integer.decode(value.replace("K", "")) * 1024;
        return Integer.decode(value);
    }

    private static AnyValue load(final InputStream in0) {
        final DefaultAnyValue any = new DefaultAnyValue();
        try (final InputStream in = in0) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);
            Element root = doc.getDocumentElement();
            load(any, root);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return any;
    }

    private static void load(final DefaultAnyValue any, final Node root) {
        final String home = System.getProperty(RESNAME_APP_HOME);
        NamedNodeMap nodes = root.getAttributes();
        if (nodes == null) return;
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            any.addValue(node.getNodeName(), node.getNodeValue().replace("${APP_HOME}", home));
        }
        NodeList children = root.getChildNodes();
        if (children == null) return;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            DefaultAnyValue sub = new DefaultAnyValue();
            load(sub, node);
            any.addValue(node.getNodeName(), sub);
        }

    }
}
