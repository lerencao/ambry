FROM java:8

# ADD  /tmp/ambry
# WORKDIR /tmp/ambry
# RUN ./gradlew allJar


RUN mkdir /ambry
WORKDIR /ambry

COPY releases/ambry.jar .

# config
RUN mkdir config

EXPOSE 6670
# jmx port
EXPOSE 9090

ADD start_server.sh .
RUN chmod a+x start_server.sh
ENTRYPOINT ["./start_server.sh"]

