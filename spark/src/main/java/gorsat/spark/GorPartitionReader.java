package gorsat.spark;

import gorsat.BatchedPipeStepIteratorAdaptor;
import gorsat.BatchedReadSource;
import gorsat.process.GorPipe;
import gorsat.process.PipeInstance;
import gorsat.process.PipeOptions;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder;
import org.apache.spark.sql.catalyst.encoders.RowEncoder;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.sql.types.StructType;
import org.gorpipe.model.gor.iterators.RowSource;
import org.gorpipe.spark.GorSparkSession;
import org.gorpipe.spark.SparkGorMonitor;
import org.gorpipe.spark.SparkGorRow;
import org.gorpipe.spark.SparkSessionFactory;
import org.gorpipe.spark.platform.JobField;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

public class GorPartitionReader implements PartitionReader<InternalRow> {
    RowSource iterator;
    SparkGorRow sparkRow;
    SparkGorMonitor sparkGorMonitor;
    GorRangeInputPartition p;
    ExpressionEncoder<Row> encoder;
    String redisUri;
    String jobId;
    String useCpp;

    public GorPartitionReader(StructType schema, GorRangeInputPartition gorRangeInputPartition, String redisUri, String jobId, String useCpp) {
        encoder = RowEncoder.apply(schema);
        sparkRow = new SparkGorRow(schema);
        p = gorRangeInputPartition;
        this.redisUri = redisUri;
        this.jobId = jobId;
        this.useCpp = useCpp;
    }

    void initIterator() {
        sparkGorMonitor = new SparkGorMonitor(redisUri,jobId) {
            @Override
            public boolean isCancelled() {
                return sparkGorMonitor.getValue(JobField.CancelFlag) != null;
            }
        };
        SparkSessionFactory sessionFactory = new SparkSessionFactory(null, Paths.get(".").toAbsolutePath().normalize().toString(), "result_cache", sparkGorMonitor);
        GorSparkSession gorPipeSession = (GorSparkSession) sessionFactory.create();
        PipeInstance pi = new PipeInstance(gorPipeSession.getGorContext());

        if(p.query!=null) {
            try {
                pi.init(p.query, false, null);

                RowSource rowSource = pi.theInputSource();
                if(p.chr!=null&&p.chr.length()>0) rowSource.setPosition(p.chr, p.start);
                iterator = new BatchedPipeStepIteratorAdaptor(rowSource, pi.getPipeStep(), rowSource.getHeader(), GorPipe.brsConfig());
            } catch(Exception e) {
                e.printStackTrace();
            }
        } else {
            boolean useNative = useCpp != null && useCpp.equalsIgnoreCase("true");
            String seek = useNative ? "cmd " : "gor ";

            Path epath = Paths.get(p.path);
            String epathstr;
            if (Files.isDirectory(epath)) {
                try {
                    epathstr = Files.walk(epath).skip(1).map(Path::toString).filter(p -> p.endsWith(".gorz")).collect(Collectors.joining(" "));
                } catch (IOException e) {
                    epathstr = p.path;
                }
            } else {
                epathstr = p.path;
            }
            String spath = useNative ? "cgor #(S:-p chr:pos) " + p.path + "}" : epathstr;
            String s = p.filterColumn != null && p.filterColumn.length() > 0 ? "-s " + p.filterColumn + " " : "";
            String path = seek + (p.filterFile == null ? p.filter == null ? s + spath : s + "-f " + p.filter + " " + spath : s + "-ff " + p.filterFile + " " + spath);
            String[] args = {path};
            PipeOptions options = new PipeOptions();
            options.parseOptions(args);
            pi.subProcessArguments(options);

            RowSource rowSource = pi.theInputSource();
            if(p.chr!=null&&p.chr.length()>0) rowSource.setPosition(p.chr, p.start);

            if (redisUri != null && redisUri.length() > 0) {
                iterator = new BatchedReadSource(rowSource, GorPipe.brsConfig(), rowSource.getHeader(), sparkGorMonitor);
            } else {
                iterator = rowSource;
            }
        }
    }

    @Override
    public boolean next() {
        if( iterator == null ) {
            initIterator();
        }
        boolean hasNext = iterator.hasNext();
        if( hasNext ) {
            org.gorpipe.model.genome.files.gor.Row gorrow = iterator.next();
            if( p.tag != null ) gorrow = gorrow.rowWithAddedColumn(p.tag);
            hasNext = p.chr == null || (gorrow.chr.equals(p.chr) && (p.end == -1 || gorrow.pos <= p.end));
            sparkRow.row = gorrow;
        }
        return hasNext;
    }

    @Override
    public InternalRow get() {
        return encoder.toRow(sparkRow);
    }

    @Override
    public void close() {
        if(iterator != null) iterator.close();
    }
}
