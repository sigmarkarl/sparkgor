package org.gorpipe.spark;

import org.apache.spark.SparkConf;
import org.apache.spark.api.python.Py4JServer;
import org.apache.spark.ml.linalg.SQLDataTypes;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.gorpipe.gor.model.Row;
import org.gorpipe.spark.udfs.CharToDoubleArray;
import org.gorpipe.spark.udfs.CharToDoubleArrayParallel;
import org.gorpipe.spark.udfs.CommaToDoubleArray;
import org.gorpipe.spark.udfs.CommaToDoubleMatrix;
import org.gorpipe.spark.udfs.CommaToIntArray;
import org.gorpipe.util.standalone.GorStandalone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.projectglow.GlowBase;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GorSparkUtilities {
    private static final Logger log = LoggerFactory.getLogger(GorSparkUtilities.class);
    private static SparkSession spark;
    private static Py4JServer py4jServer;
    private static Optional<Process> jupyterProcess;
    private static Optional<String> jupyterPath = Optional.empty();
    private static ExecutorService es;

    private GorSparkUtilities() {}
    public static Py4JServer getPyServer() {
        return py4jServer;
    }

    public static int getPyServerPort() {
        return py4jServer != null ? py4jServer.getListeningPort() : 0;
    }

    public static String getPyServerSecret() {
        return py4jServer != null ? py4jServer.secret() : "";
    }

    public static Optional<String> getJupyterPath() {
        return jupyterPath;
    }

    public static void closePySpark() {
        if(py4jServer!=null) py4jServer.shutdown();
        jupyterProcess.ifPresent(Process::destroy);
        if(es!=null) es.shutdown();
    }

    public static void initPySpark(Optional<String> standaloneRoot) {
        String pyspark = System.getenv("PYSPARK_PIN_THREAD");
        if(py4jServer==null&&pyspark!=null&&pyspark.length()>0) {
            py4jServer = new Py4JServer(spark.sparkContext().conf());
            py4jServer.start();

            GorSparkUtilities.getSparkSession();

            ProcessBuilder pb = new ProcessBuilder("/usr/local/bin/jupyter","notebook","--NotebookApp.allow_origin='https://colab.research.google.com'","--port=8888","--NotebookApp.port_retries=0");
            standaloneRoot.ifPresent(sroot -> pb.directory(Paths.get(sroot).toFile()));
            Map<String,String> env = pb.environment();
            env.put("PYSPARK_GATEWAY_PORT",Integer.toString(GorSparkUtilities.getPyServerPort()));
            env.put("PYSPARK_GATEWAY_SECRET",GorSparkUtilities.getPyServerSecret());
            env.put("PYSPARK_PIN_THREAD","true");
            try {
                Process p = pb.start();
                jupyterProcess = Optional.of(p);

                es = Executors.newFixedThreadPool(2);
                Future<String> resin = es.submit(() -> {
                    try (InputStream is = p.getInputStream()) {
                        InputStreamReader isr = new InputStreamReader(is);
                        BufferedReader br = new BufferedReader(isr);
                        jupyterPath = br.lines().map(String::trim).filter(s -> s.startsWith("http://localhost:8888/?token=")).findFirst();
                    }
                    return null;
                });
                Future<String> reserr = es.submit(() -> {
                    try (InputStream is = p.getErrorStream()) {
                        InputStreamReader isr = new InputStreamReader(is);
                        BufferedReader br = new BufferedReader(isr);
                        jupyterPath = br.lines().peek(System.err::println).map(String::trim).filter(s -> s.startsWith("http://localhost:8888/?token=")).findFirst();
                    }
                    return null;
                });
            } catch(IOException ie) {
                log.info(ie.getMessage());
                jupyterProcess = Optional.empty();
            }
        }
    }

    private static String constructRedisUri(String sparkRedisHost) {
        final String sparkRedisPort = System.getProperty("spark.redis.port");
        final String sparkRedisDb = System.getProperty("spark.redis.db");
        String ret = sparkRedisHost + ":" + (sparkRedisPort != null && sparkRedisPort.length() > 0 ? sparkRedisPort : "6379");
        return sparkRedisDb!=null && sparkRedisDb.length()>0 ? ret + "/" + sparkRedisDb : ret;
    }

    public static String getSparkGorRedisUri() {
        final String sparkRedisHost = System.getProperty("spark.redis.host");
        return sparkRedisHost != null && sparkRedisHost.length() > 0 ? constructRedisUri(sparkRedisHost) : "";
    }

    private static SparkSession newSparkSession() {
        SparkConf sparkConf = new SparkConf();
        SparkSession.Builder ssb = SparkSession.builder();
        if(!sparkConf.contains("spark.master")) {
            ssb = ssb.master("local[*]");
        }
        SparkSession spark = ssb.config(sparkConf).getOrCreate();

        spark.udf().register("chartodoublearray", new CharToDoubleArray(), DataTypes.createArrayType(DataTypes.DoubleType));
        spark.udf().register("chartodoublearrayparallel", new CharToDoubleArrayParallel(), DataTypes.createArrayType(DataTypes.DoubleType));
        spark.udf().register("todoublearray", new CommaToDoubleArray(), DataTypes.createArrayType(DataTypes.DoubleType));
        spark.udf().register("todoublematrix", new CommaToDoubleMatrix(), SQLDataTypes.MatrixType());
        spark.udf().register("tointarray", new CommaToIntArray(), DataTypes.createArrayType(DataTypes.IntegerType));

        GlowBase gb = new GlowBase();
        gb.register(spark, false);

        return spark;
    }

    public static SparkSession getSparkSession() {
        if(spark==null) {
            if (!SparkSession.getDefaultSession().isEmpty()) {
                log.info("SparkSession from default");
                spark = SparkSession.getDefaultSession().get();
            } else {
                log.info("Starting a new SparkSession");
                spark = newSparkSession();
            }
            Optional<String> standaloneRoot = GorStandalone.isStandalone() ? Optional.of(GorStandalone.getStandaloneRoot()) : Optional.empty();
            initPySpark(standaloneRoot);
        }
        return spark;
    }

    public static List<org.apache.spark.sql.Row> stream2SparkRowList(Stream<Row> str, StructType schema) {
        return str.map(p -> new SparkGorRow(p, schema)).collect(Collectors.toList());
    }
}
