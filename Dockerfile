FROM nextcode/basespark:3.1.1

# To build nextcode/basespark:[version] base image on mac
# brew install apache-spark
# cd /usr/local/Cellar/apache-spark/[version]/libexec/
# docker build --build-arg java_image_tag=15-slim --build-arg spark_uid=3000 -t nextcode/basespark:[version] -f kubernetes/dockerfiles/spark/Dockerfile .
# docker push

COPY spark/build/install/gor-scripts/lib /opt/spark/jars

USER root
RUN rm -rf /opt/spark/jars/netty-all-4.0.23.Final.jar
RUN rm -rf /opt/spark/jars/jetty-6.1.26.jar
RUN rm -rf /opt/spark/jars/jetty-util-6.1.26.jar
RUN rm -rf /opt/spark/jars/jersey-client-1.19.jar
RUN rm -rf /opt/spark/jars/jersey-server-1.19.jar
RUN rm -rf /opt/spark/jars/log4j-over-slf4j-1.7.30.jar
RUN rm -rf /opt/spark/jars/logback-core-1.2.3.jar
RUN rm -rf /opt/spark/jars/logback-classic-1.2.3.jar

USER 3000
