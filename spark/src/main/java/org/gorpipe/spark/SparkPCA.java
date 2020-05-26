package org.gorpipe.spark;

import breeze.linalg.DenseMatrix;
import com.google.common.collect.Iterators;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.mllib.linalg.Matrices;
import org.apache.spark.mllib.linalg.Matrix;
import org.apache.spark.mllib.linalg.distributed.*;
import org.apache.spark.sql.*;
import scala.Tuple2;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class SparkPCA {
    static String[] testargs = {"--projectroot","/gorproject","--freeze","plink_wes","--variants","/gorproject/testvars2.gor","--pnlist","/gorproject/testpns.txt","--partsize","4","--pcacomponents","3","--outfile","/gorproject/out.txt"};

    public static void main(String[] args) throws IOException {
        //args = testargs;
        List<String> argList = Arrays.asList(args);
        int i = argList.indexOf("--appname");
        String appName = i != -1 ? argList.get(i+1) : "pca";
        i = argList.indexOf("--freeze");
        String freeze = i != -1 ? argList.get(i+1) : null;
        i = argList.indexOf("--projectroot");
        String projectRoot = i != -1 ? argList.get(i+1) : null;
        i = argList.indexOf("--variants");
        String variants = i != -1 ? argList.get(i+1) : null;
        i = argList.indexOf("--pnlist");
        String pnlist = i != -1 ? argList.get(i+1) : null;
        i = argList.indexOf("--partsize");
        int partsize = i != -1 ? Integer.parseInt(argList.get(i+1)) : 10;
        i = argList.indexOf("--pcacomponents");
        int pcacomponents = i != -1 ? Integer.parseInt(argList.get(i+1)) : 3;
        i = argList.indexOf("--outfile");
        String outfile = i != -1 ? argList.get(i+1) : null;
        try(SparkSession spark = SparkSession.builder()/*.master("local[*]")*/.appName(appName).getOrCreate()) {
            pca(spark, projectRoot, freeze, pnlist, variants, partsize, pcacomponents, outfile);
        }
    }

    private static void pca(SparkSession spark, String projectRoot, String freeze, String pnlist, String variants, int partsize, int pcacomponents, String outfile) throws IOException {
        GorSparkSession gorSparkSession = SparkGOR.createSession(spark, projectRoot, "result_cache", 0);
        Path root = Paths.get(projectRoot);
        Path freezepath = root.resolve(freeze);
        Dataset<? extends Row> dscount = gorSparkSession.spark("spark " + variants,null);
        int varcount = (int)dscount.count();

        String freezevariants = freezepath.resolve("variants.gord").toString();
        Dataset<Row> ds = (Dataset<Row>)gorSparkSession.spark("spark <(partgor -ff "+pnlist+" -partsize "+partsize+" -dict "+freezevariants+" <(gor "+variants +
                        "| varjoin -r -l -e '?' <(gor "+freezevariants+" -nf -f #{tags})" +
                        "| rename Chrom CHROM | rename ref REF | rename alt ALT " +
                        "| calc ID chrom+'_'+pos+'_'+ref+'_'+alt " +
                        "| csvsel "+freezepath.resolve("buckets.tsv").toString()+" <(nor <(gorrow 1,1 | calc pn '#{tags}' | split pn) | select pn) -u 3 -gc id,ref,alt -vs 1 | replace values 'u'+values))"
                , null);

        JavaRDD<Tuple2<Tuple2<Object,Object>,Matrix>> dbm = ds.select("values").javaRDD().mapPartitionsWithIndex((Function2<Integer, Iterator<Row>, Iterator<Tuple2<Tuple2<Object, Object>, Matrix>>>) (pi, input) -> {
            double[] mat = null;
            Iterator<Tuple2<Tuple2<Object,Object>,Matrix>> it = Collections.emptyIterator();
            int start = 0;
            while(input.hasNext()) {
                Row row = input.next();
                String strvec = row.getString(0).substring(1);
                int len = strvec.length();
                if(mat==null) {
                    mat = new double[varcount*len];
                }
                for(int i = 0; i < len; i++) {
                    mat[start+i] = strvec.charAt(i)-'0';
                }
                start += len;
            }
            if(mat!=null) {
                Matrix matrix = Matrices.dense(mat.length/varcount,varcount,mat);
                Tuple2<Object,Object> index = new Tuple2<>(0,10*pi);
                Tuple2<Tuple2<Object,Object>,Matrix> tupmat = new Tuple2<>(index,matrix);
                return Iterators.singletonIterator(tupmat);
            }
            return it;
        },true);

        BlockMatrix mat = new BlockMatrix(dbm.rdd(),varcount,10);
        RowMatrix rowMatrix = mat.transpose().toIndexedRowMatrix().toRowMatrix();
        Matrix pc = rowMatrix.computePrincipalComponents( pcacomponents);

        // Project the rows to the linear space spanned by the top 4 principal components.
        RowMatrix projected = rowMatrix.multiply(pc);

        DenseMatrix dm = projected.toBreeze();

        Path pnpath = Paths.get(pnlist);
        List<String> pns = Files.lines(pnpath).skip(1).collect(Collectors.toList());

        Path outpath = Paths.get(outfile);
        try (BufferedWriter bw = Files.newBufferedWriter(outpath)) {
            bw.write("#PN\tcol1\tcol2\tcol3\n");
            for (int i = 0; i < pns.size(); i++) {
                bw.write(pns.get(i));
                for (int k = 0; k < pcacomponents; k++) {
                    bw.write("\t" + dm.apply(i, k));
                }
                bw.write("\n");
            }
        }
    }

    private static void coordpca(String[] args, SparkSession spark) {
        GorSparkSession gorSparkSession = SparkGOR.createSession(spark);
        JavaSparkContext javaSparkContext = JavaSparkContext.fromSparkContext(spark.sparkContext());

        Dataset<Row> dsmap = (Dataset<Row>)gorSparkSession.spark("spark -tag <(pgor -split <(gor /gorproject/brca.gor) /gorproject/plink_wes/metadata/AF.gorz" +
                "| varjoin -r -l -e '?' /gorproject/plink_wes/vep_single.gorz" +
                "| where max_consequence in ('frameshift_variant','splice_acceptor_variant','splice_donor_variant','start_lost','stop_gained','stop_lost','incomplete_terminal_codon_variant','inframe_deletion','inframe_insertion','missense_variant','protein_altering_variant','splice_region_variant')" +
                "| group chrom -count)",null);
        Map<String,Integer> rangeCount = dsmap.collectAsList().stream().collect(Collectors.toMap(r -> r.getString(4),r -> r.getInt(3)));
        Map<String,Integer> rangeSum = dsmap.collectAsList().stream().collect(Collectors.toMap(r -> r.getString(4), new Function<Row,Integer>() {
            int sum = 0;

            @Override
            public Integer apply(Row r) {
                int ret = sum;
                sum+=r.getInt(3);
                return ret;
            }
        })); //map(r -> new Map.Entry<>(r.getString(4), r.getInt(3)) {})
        Broadcast<Map<String,Integer>> bcsize = javaSparkContext.broadcast(rangeCount);
        Broadcast<Map<String,Integer>> bcsum = javaSparkContext.broadcast(rangeSum);

        //spark.udf().register( "lookupSize", new SizeLookup( bcsize ) , DataTypes.IntegerType );
        //spark.udf().register( "lookupSum", new SizeLookup( bcsum ) , DataTypes.IntegerType );
        //Dataset<? extends Row> ds = gorSparkSession.spark("spark <(pgor ref/genes.gorz | group chrom -count)",null);*/

        Dataset<? extends Row> ds = gorSparkSession.spark("spark -tag <(partgor -ff <(nor -h /gorproject/plink_wes/buckets.tsv | select 1 | top 50) -partsize 10 -dict /gorproject/plink_wes/variants.gord <(pgor -split <(gor /gorproject/brca.gor) /gorproject/plink_wes/variants.gord -nf -f #{tags} " +
                "| varjoin -r -l -e '?' /gorproject/plink_wes/vep_single.gorz" +
                "| where max_consequence in ('frameshift_variant','splice_acceptor_variant','splice_donor_variant','start_lost','stop_gained','stop_lost','incomplete_terminal_codon_variant','inframe_deletion','inframe_insertion','missense_variant','protein_altering_variant','splice_region_variant')" +
                "| rename Chrom CHROM | rename ref REF | rename alt ALT " +
                //"| varjoin -r -l -e '?' /gorproject/plink_wes/vep_single.gorz " +
                //"| where max_consequence in ('frameshift_variant','splice_acceptor_variant','splice_donor_variant','start_lost','stop_gained','stop_lost','incomplete_terminal_codon_variant','inframe_deletion','inframe_insertion','missense_variant','protein_altering_variant','splice_region_variant') "
                //"| varjoin -r -l -e 0.0 <(gor /gorproject/plink_wes/metadata/AF.gorz) " +
                //"| where isfloat(AF) and float(AF) <= 0.05 " +
                "| calc ID chrom+'_'+pos+'_'+ref+'_'+alt " +
                "| csvsel /gorproject/plink_wes/buckets.tsv <(nor -h /gorproject/plink_wes/buckets.tsv | select 1 | top 50) -u 3 -gc id,ref,alt -vs 1))"
                , null);

        //ds = ds.select("values","tag");
                //withColumn("count",org.apache.spark.sql.functions.callUDF("lookupSize", org.apache.spark.sql.functions.col("tag"))).
                //withColumn("sum",org.apache.spark.sql.functions.callUDF("lookupSum", org.apache.spark.sql.functions.col("tag")));

        /*Row[] rr = (Row[])ds2.collect();
        Arrays.stream(rr).forEach(System.err::println);

        //ds.collectAsList().forEach(System.err::println);
        //System.err.println(ds.count());

        ArrayList<Map<String,Integer>> lmi = new ArrayList<>();
        Path p = Paths.get("buckets.tsv");
        Files.lines(p).map(l -> l.split("\t")).forEach(s -> {
            int i = Integer.parseInt(s[1]);
            lmi.ensureCapacity(i);
            Map<String,Integer> mi = lmi.get(i-1);
            if(mi==null) {
                mi = new HashMap<>();
                lmi.set(i-1,mi);
            }
            mi.put(s[0],mi.size());
        });

        Broadcast brdc = javaSparkContext.broadcast(javaSparkContext);*/

        Encoder<MatrixEntry> menc = Encoders.bean(MatrixEntry.class);
        Dataset<MatrixEntry> dsm = ds.select("values","tag").mapPartitions((MapPartitionsFunction<Row, MatrixEntry>) input -> {
            if(input.hasNext()) {
                Row r = input.next();
                String tag = r.getString(1);
                int size = bcsize.getValue().get(tag);
                int sum = bcsum.getValue().get(tag);
                return Stream.concat(Stream.of(r), StreamSupport.stream(Spliterators.spliterator(input, size, Spliterator.SIZED), false)).flatMap(new Function<Row, Stream<MatrixEntry>>() {
                    int k = 0;

                    @Override
                    public Stream<MatrixEntry> apply(Row row) {
                        assert row.getString(1).equals(tag);
                        Stream<MatrixEntry> sme = row.getString(0).chars().map(c -> c-'0').asDoubleStream().mapToObj(new DoubleFunction<MatrixEntry>() {
                            int i = 0;

                            @Override
                            public MatrixEntry apply(double d) {
                                //System.err.println(i + " " + (sum+k));
                                return new MatrixEntry(sum+k,i++,d);
                            }
                        });
                        k++;
                        return sme;
                    }
                }).iterator();
            }
            return Collections.emptyIterator();
        },menc);
        /*int sum = r.getInt(1);
            return r.getString(0).chars().asDoubleStream()new MatrixEntry(0,0,1.0);
        }, menc);*/
        CoordinateMatrix cm = new CoordinateMatrix(dsm.rdd());

        /*Encoder<BlockMatrix> bmenc = Encoders.kryo(BlockMatrix.class);
        Encoder<Vector> enc = Encoders.kryo(Vector.class);
        Dataset<Vector> sparserows = ds.select("values").map((MapFunction<Row, Vector>) value -> {
            String strVector = value.getString(0);
            int[] ii = IntStream.range(0,strVector.length()).filter(i -> strVector.charAt(i)!='0').toArray();
            double[] dd = value.getString(0).chars().filter(c -> c != '0').asDoubleStream().toArray();
            return Vectors.sparse(ii.length, ii, dd);
        }, enc);*/

        /*Dataset<Tuple2<Tuple2<Object,Object>,Matrix> dbm = ds.select("values","count").mapPartitions((MapPartitionsFunction<Row, Matrix>) input -> {
            double[] mat = null;
            Iterator<Matrix> it = Collections.emptyIterator();
            int start = 0;
            int count = -1;
            while(input.hasNext()) {
                Row row = input.next();
                String strvec = row.getString(0);
                if(mat==null) {
                    count = row.getInt(1);
                    mat = new double[count*strvec.length()];
                }
                for(int i = start; i < start+strvec.length(); i++) {
                    mat[i] = strvec.charAt(i)-'0';
                }
                //double[] vec = strvec.chars().asDoubleStream().forEach(d -> mat[i++]);
                start += strvec.length();
            }
            if(mat!=null) {
                Matrix matrix = Matrices.dense(mat.length/count,count,mat);
                Iterators.singletonIterator(matrix);
            }
            return it;
        },bmenc);*/

        /*Dataset<Vector> denserows = ds.select("values").map((MapFunction<Row, Vector>) value -> Vectors.dense(value.getString(0).chars().asDoubleStream().toArray()), enc);
        JavaRDD<IndexedRow> rddi = denserows.javaRDD().zipWithIndex().map(t -> new IndexedRow(t._2, t._1));
        IndexedRowMatrix irm = new IndexedRowMatrix(rddi.rdd());
        CoordinateMatrix com = irm.toCoordinateMatrix();
        RowMatrix mat = com.transpose().toRowMatrix();*/

        // Compute the top 4 principal components.
        // Principal components are stored in a local dense matrix.

        //BlockMatrix mat = new BlockMatrix(dbm.rdd());
        //mat.transpose().toIndexedRowMatrix().toRowMatrix().
        RowMatrix rowMatrix = cm.transpose().toRowMatrix();
        Matrix pc = rowMatrix.computePrincipalComponents(3);

        // Project the rows to the linear space spanned by the top 4 principal components.
        RowMatrix projected = rowMatrix.multiply(pc);

        DenseMatrix dm = projected.toBreeze();
        System.err.println(dm.toString());
                //.collectAsList().forEach(System.err::println);*/
    }

    private static void test1(String[] args, SparkSession spark) {
        Dataset<Row> ds = spark.read().format("csv").option("header","true").option("delimiter","\t").option("inferSchema","true").load("/gorproject/ref/dbsnp/dbsnp.gor");
        ds.createOrReplaceTempView("dbsnp");
        Dataset<Row> sqlds = spark.sql("select * from dbsnp where rsids = 'rs22'");
        sqlds.write().save("/gorproject/mu.parquet");
        //System.err.println(sqlds.count());
        spark.close();
    }
}
