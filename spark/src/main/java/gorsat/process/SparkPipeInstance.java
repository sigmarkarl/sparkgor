package gorsat.process;

import gorsat.Commands.CommandParseUtilities;
import gorsat.Utilities.StringUtilities;
import io.kubernetes.client.openapi.ApiException;
import org.gorpipe.exceptions.GorSystemException;
import org.gorpipe.gor.driver.meta.SourceReferenceBuilder;
import org.gorpipe.gor.driver.providers.stream.StreamSourceFile;
import org.gorpipe.gor.driver.providers.stream.datatypes.parquet.ParquetFileIterator;
import org.gorpipe.gor.driver.providers.stream.sources.file.FileSource;
import org.gorpipe.gor.model.GenomicIterator;
import org.gorpipe.gor.monitor.GorMonitor;
import org.gorpipe.gor.session.GorContext;
import org.gorpipe.gor.util.Util;
import org.gorpipe.spark.GorSparkSession;
import org.gorpipe.spark.SparkOperatorRunner;
import org.gorpipe.spark.SparkOperatorSpecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SparkPipeInstance extends PipeInstance {
    GorSparkSession session;
    GenomicIterator genit;

    public SparkPipeInstance(GorContext context) {
        super(context);
        session = (GorSparkSession) context.getSession();
    }

    public static String getSparkOperatorYaml(String projectDir) throws IOException {
        String json = null;
        try {
            Path p = Paths.get(projectDir);
            if (Files.exists(p)) {
                Path so_json = p.resolve("config/sparkoperator.yaml");
                if (Files.exists(so_json)) json = new String(Files.readAllBytes(so_json));
            }
        } finally {
            if (json == null) {
                json = Util.readAndCloseStream(SparkPipeInstance.class.getResourceAsStream("sparkoperator.yaml"));
            }
        }
        return json;
    }

    @Override
    public void init(String params, GorMonitor gm) {
        String[] commands = CommandParseUtilities.quoteSafeSplit(params,';');
        String lastcommand = commands[commands.length-1];
        String[] resourceSplit = GorJavaUtilities.splitResourceHints(lastcommand,"spec.");
        String resourceHints = resourceSplit[1];
        if(resourceHints==null||resourceHints.length()==0) {
            super.init(params, gm);
        } else {
            String uristr = session.redisUri();
            String requestId = session.getRequestId();
            String projectDir = session.getProjectContext().getRoot();
            String queries;
            if(commands.length>1) {
                queries = String.join(";", Arrays.copyOfRange(commands,0,commands.length-1)) + ";" + resourceSplit[0];
            } else {
                queries = resourceSplit[0];
            }
            String fingerprint = StringUtilities.createMD5(queries);
            Path projectPath = Paths.get(projectDir);
            Path cachePath = projectPath.resolve("result_cache");
            String cachefiles = fingerprint+".parquet";
            Path cachefilepath = cachePath.resolve(cachefiles);
            String cachefilestr = cachefilepath.toAbsolutePath().normalize().toString();
            String jobid = fingerprint;

            if(!Files.exists(cachefilepath)) {
                SparkOperatorSpecs sparkOperatorSpecs = new SparkOperatorSpecs();

                List<Map<String, Object>> vollist = new ArrayList<>();
                vollist.add(Map.of("name","volnfs","persistentVolumeClaim",Map.of("claimName","pvc-gor-nfs-v2")));
                sparkOperatorSpecs.addConfig("spec.volumes", vollist);

                List<Map<String, Object>> listMounts = new ArrayList<>();
                listMounts.add(Map.of("name", "volnfs", "mountPath", projectDir));
                sparkOperatorSpecs.addConfig("spec.executor.volumeMounts", listMounts);
                sparkOperatorSpecs.addConfig("spec.driver.volumeMounts", listMounts);

                try {
                    Path projectBasePath = Paths.get("/mnt/csa");
                    Path projectRealPath = projectPath.toRealPath().toAbsolutePath();
                    Path projectSubPath = projectBasePath.relativize(projectRealPath);

                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.gorproject.options.claimName\"", "pvc-gor-nfs-v2");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.gorproject.mount.path\"",projectRealPath.toString());
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.gorproject.mount.subPath\"",projectSubPath);
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.gorproject.options.claimName\"", "pvc-gor-nfs-v2");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.gorproject.mount.path\"",projectRealPath.toString());
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.gorproject.mount.subPath\"",projectSubPath);

                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.data.options.claimName\"", "pvc-phenocat-nfs");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.data.mount.path\"","/mnt/csa/data");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.data.mount.subPath\"","data");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.data.options.claimName\"", "pvc-phenocat-nfs");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.data.mount.path\"","/mnt/csa/data");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.data.mount.subPath\"","data");

                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.volumes.options.claimName\"", "pvc-sm-nfs");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.volumes.mount.path\"","/mnt/csa/volumes");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.driver.volumes.persistentVolumeClaim.volumes.mount.subPath\"","volumes");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.volumes.options.claimName\"", "pvc-sm-nfs");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.volumes.mount.path\"","/mnt/csa/volumes");
                    sparkOperatorSpecs.addConfig("spec.sparkConf.\"spark.kubernetes.executor.volumes.persistentVolumeClaim.volumes.mount.subPath\"","volumes");

                    String[] args = new String[]{uristr, requestId, projectDir, queries, fingerprint, cachefilestr, jobid};
                    List<String> arglist = Arrays.asList(args);
                    sparkOperatorSpecs.addConfig("spec.arguments", arglist);

                    String sparkApplicationName = "gorquery-" + jobid;
                    sparkOperatorSpecs.addConfig("metadata.name", sparkApplicationName);

                    for (String config : resourceHints.split(" ")) {
                        String[] confSplit = config.split("=");
                        try {
                            Integer ii = Integer.parseInt(confSplit[1]);
                            sparkOperatorSpecs.addConfig(confSplit[0], ii);
                        } catch (NumberFormatException ne) {
                            sparkOperatorSpecs.addConfig(confSplit[0], confSplit[1]);
                        }
                    }

                    String yaml = getSparkOperatorYaml(projectDir);
                    SparkOperatorRunner sparkOperatorRunner = new SparkOperatorRunner(session.getKubeNamespace());
                    sparkOperatorRunner.run(yaml, projectDir, sparkOperatorSpecs);
                    sparkOperatorRunner.waitForSparkApplicationToComplete(gm,sparkApplicationName);
                } catch (IOException | ApiException | InterruptedException e) {
                    throw new GorSystemException(e);
                }
            }

            SourceReferenceBuilder srb = new SourceReferenceBuilder(cachefilestr);
            srb.commonRoot(cachePath.toString());
            FileSource fileSource = new FileSource(srb.build());
            StreamSourceFile ssf = new StreamSourceFile(fileSource);
            genit = new ParquetFileIterator(ssf);
        }
    }

    @Override
    public String getHeader() {
        if(genit!=null) return genit.getHeader();
        return super.getHeader();
    }

    @Override
    public void seek(String chr, int pos) {
        if(genit!=null) genit.seek(chr,pos);
        super.seek(chr,pos);
    }

    @Override
    public boolean hasNext() {
        if(genit!=null) return genit.hasNext();
        return super.hasNext();
    }

    @Override
    public String next() {
        if(genit!=null) return genit.next().toString();
        return super.next();
    }
}
