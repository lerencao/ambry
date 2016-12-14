FROM java:8

WORKDIR /
RUN mkdir /ambry

WORKDIR /ambry
COPY bin/ambry.jar .

ADD config config
EXPOSE 6670


ADD entrypoint.sh .
RUN chmod a+x entrypoint.sh
ENTRYPOINT ["./entrypoint.sh"]

